package clanker.craft.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;

/**
 * Simple centralized config resolver for ClankerCraft.
 * Precedence: System property -> Environment variable -> Fabric config file (clankercraft-llm.properties).
 *
 * Provides convenience alias resolution so users can provide any of the listed keys.
 */
public final class Config {
    private static volatile Properties cached;

    private Config() {}

    public static String get(String... keys) {
        Objects.requireNonNull(keys, "keys");
        for (String k : keys) {
            if (k == null || k.isBlank()) continue;
            // 1) System property
            String v = System.getProperty(k);
            if (v != null && !v.isBlank()) return v.trim();
            // 2) Environment variable
            v = System.getenv(k);
            if (v != null && !v.isBlank()) return v.trim();
            // 3) Properties file
            v = props().getProperty(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    public static String getOrDefault(String defaultValue, String... keys) {
        String v = get(keys);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    public static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties");
    }

    private static Properties props() {
        Properties p = cached;
        if (p != null) return p;
        synchronized (Config.class) {
            if (cached != null) return cached;
            Properties loaded = new Properties();
            try {
                Path f = configFile();
                if (Files.exists(f)) {
                    try (InputStream in = Files.newInputStream(f)) {
                        loaded.load(in);
                    }
                }
            } catch (Exception ignored) {}
            cached = loaded;
            return cached;
        }
    }

    // Convenience aliases for common keys
    public static String geminiApiKey() {
        return get(
                "GOOGLE_AI_STUDIO_API_KEY",
                "GEMINI_API_KEY",
                "GOOGLE_AI_API_KEY",
                "AI_STUDIO_API_KEY"
        );
    }

    public static String geminiModelOrDefault(String def) {
        return getOrDefault(def,
                "GEMINI_MODEL",
                "GOOGLE_AI_STUDIO_MODEL",
                "LLM_MODEL"
        );
    }

    public static String gcpProjectId() {
        return get(
                "GOOGLE_CLOUD_PROJECT_ID",
                "GCP_PROJECT_ID",
                "PROJECT_ID"
        );
    }

    public static String gcpLocationOrDefault(String def) {
        return getOrDefault(def,
                "GCP_LOCATION",
                "GOOGLE_CLOUD_LOCATION",
                "LOCATION"
        );
    }

    public static String googleCredentialsPath() {
        return get(
                "GOOGLE_APPLICATION_CREDENTIALS",
                "GOOGLE_CLOUD_CREDENTIALS",
                "GOOGLE_ADC_JSON"
        );
    }

    public static String imagenModelOrDefault(String def) {
        return getOrDefault(def,
                "IMAGEN_MODEL",
                "VERTEX_IMAGEN_MODEL"
        );
    }

    public static String lyriaModelOrDefault(String def) {
        return getOrDefault(def,
                "VERTEX_LYRIA_MODEL",
                "LYRIA_MODEL"
        );
    }

    public static String ttsApiKey() {
        return get(
                // Accept both; prefer GOOGLE_TTS_API_KEY in docs
                "GOOGLE_TTS_API_KEY",
                "GOOGLE_CLOUD_API_KEY",
                "TEXT_TO_SPEECH_API_KEY"
        );
    }

    public static String ttsLanguageOrDefault(String def) {
        return getOrDefault(def, "TTS_LANGUAGE_CODE");
    }

    public static String ttsVoiceName() { return get("TTS_VOICE_NAME"); }
    public static String ttsSpeakingRate() { return get("TTS_SPEAKING_RATE"); }
    public static String ttsPitch() { return get("TTS_PITCH"); }

    public static String personalityName() { return get("CLANKER_PERSONALITY"); }

    public static String languageOrDefault(String def) {
        return getOrDefault(def,
                "CLANKER_LANGUAGE",
                "LANGUAGE"
        );
    }
}
