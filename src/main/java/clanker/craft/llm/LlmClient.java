package clanker.craft.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

/**
 * Minimal Google AI Studio (Gemini) client.
 * Sources for configuration (in order):
 * 1) JVM system properties: -DGOOGLE_AI_API_KEY=... and -DGEMINI_MODEL=...
 * 2) Environment variables: GOOGLE_AI_API_KEY and GEMINI_MODEL
 * 3) Config file: ${fabricConfigDir}/clankercraft-llm.properties with keys GOOGLE_AI_API_KEY and GEMINI_MODEL
 */
public class LlmClient {
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final String apiKey;
    private final String model;

    public LlmClient() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.apiKey = resolveApiKey();
        String m = resolveModel();
        if (m == null || m.isBlank()) {
            // Default to a modern fast model with explicit -latest suffix
            m = "gemini-2.5-flash-latest";
        }
        this.model = m;
    }

    public boolean isEnabled() { return apiKey != null && !apiKey.isBlank(); }
    public String getModel() { return model; }

    private static String resolveApiKey() {
        // 1) System property
        String v = System.getProperty("GOOGLE_AI_API_KEY");
        if (v != null && !v.isBlank()) return v.trim();
        // 2) Env var
        v = System.getenv("GOOGLE_AI_API_KEY");
        if (v != null && !v.isBlank()) return v.trim();
        // 3) Config file
        return readFromConfig("GOOGLE_AI_API_KEY");
    }

    private static String resolveModel() {
        String v = System.getProperty("GEMINI_MODEL");
        if (v != null && !v.isBlank()) return v.trim();
        v = System.getenv("GEMINI_MODEL");
        if (v != null && !v.isBlank()) return v.trim();
        v = readFromConfig("GEMINI_MODEL");
        if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    private static String readFromConfig(String key) {
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            Path file = configDir.resolve("clankercraft-llm.properties");
            if (!Files.exists(file)) return null;
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
            String v = props.getProperty(key);
            return (v == null || v.isBlank()) ? null : v.trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Generates a model reply given a conversation history and the latest user message.
     * History format: alternating roles in a simple string pair list: ["user: ...", "model: ...", ...]
     */
    public String generate(List<String> history, String userInput) throws Exception {
        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        for (String turn : history) {
            int idx = turn.indexOf(":");
            String role = idx > 0 ? turn.substring(0, idx).trim() : "user";
            String text = idx > 0 ? turn.substring(idx + 1).trim() : turn;
            contents.add(content(role, text));
        }
        contents.add(content("user", userInput));
        body.add("contents", contents);

        // Try configured/default model first (v1 endpoint)
        Response r = call(body, model);
        if (r.ok) return r.text;

        // On 404 NOT_FOUND, try common fallbacks automatically
        if (r.statusCode == 404) {
            // 1) If user supplied without -latest, try adding -latest
            if (!model.endsWith("-latest")) {
                Response r2 = call(body, model + "-latest");
                if (r2.ok) return r2.text;
            }
            // 2) Try a modern flash model
            Response r3 = call(body, "gemini-2.5-flash-latest");
            if (r3.ok) return r3.text;
            // 3) Try gemini-2.0-flash
            Response r4 = call(body, "gemini-2.0-flash");
            if (r4.ok) return r4.text;
            // 4) Try legacy
            Response r5 = call(body, "gemini-1.5-flash-latest");
            if (r5.ok) return r5.text;
            throw new RuntimeException("Gemini model not found. Tried variants including -latest and common flash models. Last error: " + r.body);
        }

        // Other errors: bubble up with detail
        throw new RuntimeException("Gemini HTTP " + r.statusCode + ": " + r.body);
    }

    private Response call(JsonObject body, String modelToUse) throws Exception {
        URI uri = URI.create("https://generativelanguage.googleapis.com/v1/models/" + modelToUse + ":generateContent?key=" + apiKey);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 == 2) {
            String text = parseText(resp.body());
            return new Response(true, resp.statusCode(), resp.body(), text);
        }
        return new Response(false, resp.statusCode(), resp.body(), null);
    }

    private static String parseText(String body) {
        JsonObject json = GSON.fromJson(body, JsonObject.class);
        JsonArray candidates = json.has("candidates") && json.get("candidates").isJsonArray() ? json.getAsJsonArray("candidates") : null;
        if (candidates == null || candidates.size() == 0) return "...";
        JsonObject first = candidates.get(0).getAsJsonObject();
        JsonObject content = first.getAsJsonObject("content");
        if (content == null) return "...";
        JsonArray parts = content.getAsJsonArray("parts");
        if (parts == null || parts.size() == 0) return "...";
        JsonElement part = parts.get(0);
        if (!part.isJsonObject()) return "...";
        JsonObject partObj = part.getAsJsonObject();
        return partObj.has("text") ? partObj.get("text").getAsString() : "...";
    }

    private static JsonObject content(String role, String text) {
        JsonObject c = new JsonObject();
        c.addProperty("role", role.equalsIgnoreCase("model") ? "model" : "user");
        JsonArray parts = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        parts.add(p);
        c.add("parts", parts);
        return c;
    }

    private static final class Response {
        final boolean ok;
        final int statusCode;
        final String body;
        final String text;
        Response(boolean ok, int statusCode, String body, String text) {
            this.ok = ok; this.statusCode = statusCode; this.body = body; this.text = text;
        }
    }
}
