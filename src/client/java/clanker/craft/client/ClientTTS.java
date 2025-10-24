package clanker.craft.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import clanker.craft.config.Config;
import clanker.craft.i18n.LanguageManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import javax.sound.sampled.*;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public final class ClientTTS {
    private static final ClientTTS INSTANCE = new ClientTTS();
    public static ClientTTS get() { return INSTANCE; }

    // HTTP client and executor service
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Clanker-TTS");
        t.setDaemon(true);
        return t;
    });
    private static final Gson GSON = new Gson();

    private volatile boolean quotaExceeded = false;

    // Track active OpenAL sources for cleanup on client ticks (must run on client thread)
    private final List<int[]> activeAl = new ArrayList<>(); // entries: {sourceId, bufferId}

    private ClientTTS() {}

    // Called from client tick (registered in client init)
    public void tick(MinecraftClient client) {
        // Clean up finished sources
        for (Iterator<int[]> it = activeAl.iterator(); it.hasNext();) {
            int[] pair = it.next();
            int src = pair[0];
            int state = AL10.alGetSourcei(src, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_STOPPED) {
                AL10.alDeleteSources(src);
                AL10.alDeleteBuffers(pair[1]);
                it.remove();
            }
        }
    }

    // Backward-compatible entry point (non-positional)
    public void speakAsync(MinecraftClient client, String text) {
        speakAsync(client, text, -1);
    }

    // New entry point with positional playback via entityId (if available)
    public void speakAsync(MinecraftClient client, String text, int entityId) {
        if (quotaExceeded) {
            notifyClient(client, LanguageManager.get("clanker.tts.quota_exceeded"));
            return;
        }
        exec.submit(() -> {
            try {
                PcmAudio pcm = synthesizePcm(text);
                if (pcm == null || pcm.data.length == 0) {
                    notifyClient(client, LanguageManager.get("clanker.tts.unavailable"));
                    return;
                }
                // Schedule OpenAL playback on client thread
                client.execute(() -> {
                    try {
                        playOpenAlAtEntity(client, pcm, entityId);
                    } catch (Throwable alErr) {
                        // Fallback to Java Sound if OpenAL fails
                        try { playPcm(pcm); } catch (Exception ignored) {}
                    }
                });
            } catch (QuotaException qe) {
                quotaExceeded = true;
                notifyClient(client, LanguageManager.get("clanker.tts.quota_exceeded_full"));
            } catch (Exception e) {
                notifyClient(client, LanguageManager.format("clanker.tts.error", e.getMessage()));
            }
        });
    }

    private void playOpenAlAtEntity(MinecraftClient client, PcmAudio a, int entityId) {
        Entity src = (client != null && client.world != null && entityId >= 0) ? client.world.getEntityById(entityId) : null;
        // Prepare OpenAL buffer
        int buffer = AL10.alGenBuffers();
        ByteBuffer bb = BufferUtils.createByteBuffer(a.data.length);
        bb.put(a.data).flip();
        // Mono 16-bit little endian PCM
        int format = (a.channels == 1) ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
        AL10.alBufferData(buffer, format, bb, a.sampleRate);

        int source = AL10.alGenSources();
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        // Reference distance and rolloff to feel like Minecraft
        AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 2.0f);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
        AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 64.0f);
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);

        float sx, sy, sz;
        if (src != null) {
            sx = (float) src.getX();
            sy = (float) (src.getY() + src.getStandingEyeHeight());
            sz = (float) src.getZ();
        } else if (client != null && client.player != null) {
            // Fallback: play at player position
            sx = (float) client.player.getX();
            sy = (float) client.player.getEyeY();
            sz = (float) client.player.getZ();
        } else {
            sx = sy = sz = 0f;
        }
        AL10.alSource3f(source, AL10.AL_POSITION, sx, sy, sz);

        AL10.alSourcePlay(source);
        // Track for cleanup
        activeAl.add(new int[]{source, buffer});
    }

    private static void notifyClient(MinecraftClient client, String msg) {
        var p = client.player;
        if (p != null) p.sendMessage(Text.literal(msg), false);
    }

    // Prefer dedicated TTS key; accept aliases via Config
    private static String resolveTtsKey() {
        return Config.ttsApiKey();
    }

    /**
     * Sanitize text for TTS by removing or replacing symbols that shouldn't be pronounced.
     * This includes markdown formatting symbols, special characters, and other non-verbal elements.
     */
    private static String sanitizeTextForTts(String text) {
        if (text == null || text.isEmpty()) return text;
        
        // Remove markdown-style emphasis markers (*, _, ~, `)
        String sanitized = text.replaceAll("[*_~`]", "");
        
        // Remove other common symbols that shouldn't be spoken
        sanitized = sanitized.replaceAll("[#@$%^&+=<>\\[\\]{}|\\\\]", "");
        
        // Replace multiple spaces with single space
        sanitized = sanitized.replaceAll("\\s+", " ");
        
        // Trim leading/trailing whitespace
        sanitized = sanitized.trim();
        
        return sanitized;
    }

    private PcmAudio synthesizePcm(String text) throws Exception {
        String apiKey = resolveTtsKey();
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("No GOOGLE_TTS_API_KEY configured (you can also set GOOGLE_CLOUD_API_KEY). See clankercraft-llm.properties");

        // Sanitize text to remove symbols that shouldn't be pronounced
        String sanitizedText = sanitizeTextForTts(text);

        // Cloud Text-to-Speech v1 request for 24 kHz, mono, 16-bit PCM (LINEAR16)
        int rate = 24000;
        JsonObject body = new JsonObject();
        JsonObject input = new JsonObject();
        input.addProperty("text", sanitizedText);
        body.add("input", input);

        JsonObject voice = new JsonObject();
        String lang = resolve("TTS_LANGUAGE_CODE");
        // If no explicit TTS language is set, derive it from CLANKER_LANGUAGE
        if (lang == null || lang.isBlank()) {
            String clankerLang = LanguageManager.getConfiguredLanguage();
            lang = mapLanguageCodeToTTS(clankerLang);
        }
        voice.addProperty("languageCode", lang);
        String voiceName = resolve("TTS_VOICE_NAME"); // e.g., "en-US-Chirp-HD-F"
        if (voiceName != null && !voiceName.isBlank()) voice.addProperty("name", voiceName);
        body.add("voice", voice);

        JsonObject audioCfg = new JsonObject();
        audioCfg.addProperty("audioEncoding", "LINEAR16");
        audioCfg.addProperty("sampleRateHertz", rate);

        // Chirp 3: HD voices (e.g., en-US-Chirp-HD-F) do not support speakingRate/pitch.
        boolean isChirp = voiceName != null && voiceName.toLowerCase().contains("chirp");
        if (!isChirp) {
            String rateStr = resolve("TTS_SPEAKING_RATE");
            if (rateStr != null && !rateStr.isBlank()) {
                try { audioCfg.addProperty("speakingRate", Double.parseDouble(rateStr)); } catch (NumberFormatException ignored) {}
            }
            String pitchStr = resolve("TTS_PITCH");
            if (pitchStr != null && !pitchStr.isBlank()) {
                try { audioCfg.addProperty("pitch", Double.parseDouble(pitchStr)); } catch (NumberFormatException ignored) {}
            }
        }
        body.add("audioConfig", audioCfg);

        URI uri = URI.create("https://texttospeech.googleapis.com/v1/text:synthesize?key=" + apiKey);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = resp.statusCode();
        if (sc == 429) throw new QuotaException();
        if (sc / 100 != 2) throw new RuntimeException("HTTP " + sc + ": " + resp.body());

        JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
        if (!json.has("audioContent")) return null;
        String b64 = json.get("audioContent").getAsString();
        byte[] pcm = Base64.getDecoder().decode(b64);
        return new PcmAudio(pcm, rate, 1);
    }

    private static void playPcm(PcmAudio a) throws Exception {
        AudioFormat format = new AudioFormat(a.sampleRate, 16, a.channels, true, false); // little-endian
        Clip clip = AudioSystem.getClip();
        clip.open(format, a.data, 0, a.data.length);
        clip.addLineListener(event -> { if (event.getType() == LineEvent.Type.STOP) clip.close(); });
        clip.start();
    }

    private record PcmAudio(byte[] data, int sampleRate, int channels) {}

    private static String resolve(String key) {
        // Prefer centralized config
        String v0 = Config.get(key);
        if (v0 != null) return v0;

        // JVM property
        String v = System.getProperty(key);
        if (v != null && !v.isBlank()) return v.trim();
        // env var
        v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        // config file
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path file = configDir.resolve("clankercraft-llm.properties");
            if (!Files.exists(file)) return null;
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) { props.load(in); }
            v = props.getProperty(key);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Map a simple language code (e.g., "en", "es") to a TTS language code (e.g., "en-US", "es-ES").
     */
    private static String mapLanguageCodeToTTS(String langCode) {
        return switch (langCode.toLowerCase()) {
            case "en" -> "en-US";
            case "es" -> "es-ES";
            case "fr" -> "fr-FR";
            case "de" -> "de-DE";
            case "it" -> "it-IT";
            case "pt" -> "pt-PT";
            default -> "en-US";
        };
    }

    private static double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (v > hi) ? hi : v;
    }

    private static final class QuotaException extends Exception {}
}
