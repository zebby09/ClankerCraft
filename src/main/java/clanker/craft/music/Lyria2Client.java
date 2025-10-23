package clanker.craft.music;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import clanker.craft.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * A client for Vertex AI Lyria 2 music generation that saves a music file.
 */
public final class Lyria2Client {
    private static final Logger LOGGER = LoggerFactory.getLogger("ClankerCraft-Lyria2");
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final HttpClient http = HttpClient.newHttpClient();
    private final GoogleCredentials credentials;
    private final String projectId;
    private final String location;
    private final String model;

    public Lyria2Client() {
        this.projectId = Config.gcpProjectId();
        String loc = Config.gcpLocationOrDefault("us-central1");
        this.location = (loc == null || loc.isBlank()) ? "us-central1" : loc.trim();
        String m = Config.lyriaModelOrDefault("lyria-002");
        this.model = (m == null || m.isBlank()) ? "lyria-002" : m.trim();
        this.credentials = loadCredentials();
        try { Files.createDirectories(getOutputDir()); } catch (Exception ignored) {}
    }

    public boolean isEnabled() {
        return credentials != null && projectId != null && !projectId.isBlank();
    }

    public Path generateAndSave(String prompt) throws Exception {
        if (!isEnabled()) throw new IllegalStateException("Lyria is not configured");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt is empty");

        String accessToken = getAccessToken();
        if (accessToken == null || accessToken.isBlank()) throw new IllegalStateException("Failed to obtain Google access token");

        // Try :predict then :generate, and model variants with/without version suffix if user didn't provide one.
        String[] endpoints = new String[]{":predict", ":generate"};
        String baseModel = model;
        String[] modelVariants = baseModel.contains("/") || baseModel.contains("@")
                ? new String[]{baseModel}
                : new String[]{baseModel, baseModel + "@001", baseModel + "@002"};

        byte[] wavBytes = null; String usedModel = null; String usedEndpoint = null; int lastStatus = -1; String lastBody = null;

        for (String mv : modelVariants) {
            for (String ep : endpoints) {
                URI uri = URI.create("https://" + location + "-aiplatform.googleapis.com/v1/projects/" + projectId + "/locations/" + location + "/publishers/google/models/" + mv + ep);

                JsonObject body = new JsonObject();
                JsonArray instances = new JsonArray();
                JsonObject instance = new JsonObject();
                // Common fields (actual schema may differ; this aims to be compatible across revisions)
                instance.addProperty("prompt", prompt);
                instance.addProperty("audioFormat", "wav");
                instance.addProperty("sampleRateHertz", 44100);
                // Optional guidance knobs
                instance.addProperty("durationSeconds", 30); // keep short for testing pipeline
                instances.add(instance);
                body.add("instances", instances);
                JsonObject parameters = new JsonObject();
                // Provide an explicit response mime type if supported
                parameters.addProperty("responseMimeType", "audio/wav");
                body.add("parameters", parameters);

                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> resp;
                try {
                    resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    lastBody = e.getMessage();
                    continue; // try next variant
                }
                lastStatus = resp.statusCode();
                lastBody = resp.body();
                if (resp.statusCode() / 100 == 2) {
                    byte[] audio = extractAudioBytes(resp.body());
                    if (audio != null && audio.length > 0) {
                        wavBytes = audio;
                        usedModel = mv; usedEndpoint = ep;
                        break;
                    }
                } else if (resp.statusCode() == 404) {
                    // Try next model variant/endpoint
                    continue;
                } else {
                    // Other errors — still allow trying next combination
                    continue;
                }
            }
            if (wavBytes != null) break;
        }

        if (wavBytes == null || wavBytes.length == 0) {
            String msg = "Lyria request failed" + (lastStatus > 0 ? (" (lastStatus=" + lastStatus + ")") : "") + ": " + (lastBody == null ? "(no body)" : truncate(lastBody, 600));
            throw new RuntimeException(msg);
        }

        Path outDir = getOutputDir();
        String safe = slug(prompt);
        Path out = outDir.resolve("lyria-" + TS.format(LocalDateTime.now()) + (safe.isEmpty() ? "" : ("-" + safe)) + ".wav");
        Files.write(out, wavBytes);
        try { LOGGER.info("Lyria generated WAV (bytes={}, model={}, endpoint={}, file={})", wavBytes.length, usedModel, usedEndpoint, out.toAbsolutePath()); } catch (Throwable ignored) {}
        return out;
    }

    private static String readFromConfig(String key) {
        // Deprecated: use Config helper

        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path file = configDir.resolve("clankercraft-llm.properties");
            if (!Files.exists(file)) return null;
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) { props.load(in); }
            String v = props.getProperty(key);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (Throwable ignored) { return null; }
    }

    private GoogleCredentials loadCredentials() {
        try {
            String path = Config.googleCredentialsPath();
            GoogleCredentials creds;
            if (path != null && !path.isBlank()) {
                try (InputStream in = Files.newInputStream(Path.of(path))) {
                    creds = GoogleCredentials.fromStream(in);
                }
            } else {
                creds = GoogleCredentials.getApplicationDefault();
            }
            if (creds == null) return null;
            return creds.createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
        } catch (Exception e) {
            return null;
        }
    }

    private String getAccessToken() throws java.io.IOException {
        if (credentials == null) return null;
        synchronized (credentials) {
            AccessToken t = credentials.getAccessToken();
            if (t == null || t.getTokenValue() == null || t.getExpirationTime() == null || t.getExpirationTime().getTime() - System.currentTimeMillis() < 60_000) {
                credentials.refreshIfExpired();
                t = credentials.getAccessToken();
                if (t == null || t.getTokenValue() == null) {
                    t = credentials.refreshAccessToken();
                }
            }
            return (t == null) ? null : t.getTokenValue();
        }
    }

    private static byte[] extractAudioBytes(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("predictions")) return null;
            JsonArray preds = json.getAsJsonArray("predictions");
            if (preds.size() == 0) return null;
            JsonElement p0 = preds.get(0);
            // Heuristics: try common fields first
            byte[] b;
            b = tryGetFieldBase64(p0, List.of(
                    List.of("audioBytes"),
                    List.of("bytesBase64Encoded"),
                    List.of("audio", "bytesBase64Encoded"),
                    List.of("media", "bytesBase64Encoded"),
                    List.of("samples", "0", "bytesBase64Encoded"),
                    List.of("audios", "0", "bytesBase64Encoded"),
                    List.of("audios", "0"),
                    List.of("audio")
            ));
            if (b != null && b.length > 0) return b;
            // Fallback: deep search for any plausible base64 chunk that decodes to WAV (RIFF)
            b = deepSearchFirstBase64(p0);
            if (b != null && b.length > 0) return b;
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] tryGetFieldBase64(JsonElement root, List<List<String>> paths) {
        for (List<String> path : paths) {
            JsonElement cur = root;
            boolean ok = true;
            for (String key : path) {
                if (cur == null) { ok = false; break; }
                if (cur.isJsonArray()) {
                    try {
                        int idx = Integer.parseInt(key);
                        JsonArray arr = cur.getAsJsonArray();
                        if (idx < 0 || idx >= arr.size()) { ok = false; break; }
                        cur = arr.get(idx);
                    } catch (NumberFormatException nfe) { ok = false; break; }
                } else if (cur.isJsonObject()) {
                    JsonObject obj = cur.getAsJsonObject();
                    if (!obj.has(key)) { ok = false; break; }
                    cur = obj.get(key);
                } else { ok = false; break; }
            }
            if (!ok || cur == null) continue;
            if (cur.isJsonPrimitive() && cur.getAsJsonPrimitive().isString()) {
                byte[] data = decodeBase64(cur.getAsString());
                if (looksLikeWav(data)) return data;
                // If not a WAV header, still return data (model may output MP3 but we asked for WAV)
                if (data != null && data.length > 0) return data;
            }
        }
        return null;
    }

    private static byte[] deepSearchFirstBase64(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            byte[] data = decodeBase64(el.getAsString());
            if (data != null && data.length > 0) return data;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                byte[] data = deepSearchFirstBase64(e);
                if (data != null && data.length > 0) return data;
            }
        } else if (el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                byte[] data = deepSearchFirstBase64(e.getValue());
                if (data != null && data.length > 0) return data;
            }
        }
        return null;
    }

    private static boolean looksLikeWav(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return false;
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
               bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
    }

    private static byte[] decodeBase64(String s) {
        if (s == null || s.isEmpty()) return null;
        // Allow data URI prefix
        int comma = s.indexOf(",");
        String b64 = (s.startsWith("data:")) && comma > 0 ? s.substring(comma + 1) : s;
        try { return Base64.getDecoder().decode(b64); }
        catch (IllegalArgumentException iae) { return null; }
    }

    private static Path getOutputDir() {
        return FabricLoader.getInstance().getGameDir().resolve("MusicSamples");
    }

    private static String slug(String s) {
        String cleaned = s.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        cleaned = cleaned.replaceAll("-+", "-");
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40);
        return cleaned.replaceAll("^-|-$", "");
    }

    private static String truncate(String s, int max) { return (s == null || s.length() <= max) ? s : s.substring(0, max) + "..."; }
}

