package clanker.craft.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

public final class ClientTts {
    private static final ClientTts INSTANCE = new ClientTts();
    public static ClientTts get() { return INSTANCE; }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiazJaquet-TTS");
        t.setDaemon(true);
        return t;
    });
    private static final Gson GSON = new Gson();

    private volatile boolean quotaExceeded = false;

    private ClientTts() {}

    public void speakAsync(MinecraftClient client, String text) {
        if (quotaExceeded) {
            notifyClient(client, "TTS quota exceeded. Playing chat only.");
            return;
        }
        exec.submit(() -> {
            try {
                PcmAudio pcm = synthesizePcm(text);
                if (pcm == null || pcm.data.length == 0) {
                    notifyClient(client, "TTS unavailable for this response.");
                    return;
                }
                playPcm(pcm);
            } catch (QuotaException qe) {
                quotaExceeded = true;
                notifyClient(client, "TTS quota exceeded. Further audio will be skipped today.");
            } catch (Exception e) {
                notifyClient(client, "TTS error: " + e.getMessage());
            }
        });
    }

    private static void notifyClient(MinecraftClient client, String msg) {
        var p = client.player;
        if (p != null) p.sendMessage(Text.literal(msg), false);
    }

    // Prefer dedicated TTS key; no fallback to AI Studio key
    private static String resolveTtsKey() {
        return resolve("GOOGLE_TTS_API_KEY");
    }

    private PcmAudio synthesizePcm(String text) throws Exception {
        String apiKey = resolveTtsKey();
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("No GOOGLE_TTS_API_KEY configured for TTS");

        // Cloud Text-to-Speech v1 request for 24 kHz, mono, 16-bit PCM (LINEAR16)
        int rate = 24000;
        JsonObject body = new JsonObject();
        JsonObject input = new JsonObject();
        input.addProperty("text", text);
        body.add("input", input);

        JsonObject voice = new JsonObject();
        String lang = resolve("TTS_LANGUAGE_CODE");
        if (lang == null || lang.isBlank()) lang = "en-US";
        voice.addProperty("languageCode", lang);
        String voiceName = resolve("TTS_VOICE_NAME"); // e.g., "en-US-Neural2-C"
        if (voiceName != null && !voiceName.isBlank()) voice.addProperty("name", voiceName);
        body.add("voice", voice);

        JsonObject audioCfg = new JsonObject();
        audioCfg.addProperty("audioEncoding", "LINEAR16");
        audioCfg.addProperty("sampleRateHertz", rate);
        String rateStr = resolve("TTS_SPEAKING_RATE");
        if (rateStr != null && !rateStr.isBlank()) {
            try { audioCfg.addProperty("speakingRate", Double.parseDouble(rateStr)); } catch (NumberFormatException ignored) {}
        }
        String pitchStr = resolve("TTS_PITCH");
        if (pitchStr != null && !pitchStr.isBlank()) {
            try { audioCfg.addProperty("pitch", Double.parseDouble(pitchStr)); } catch (NumberFormatException ignored) {}
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
        clip.start();
    }

    private record PcmAudio(byte[] data, int sampleRate, int channels) {}

    private static String resolve(String key) {
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

    private static final class QuotaException extends Exception {}
}
