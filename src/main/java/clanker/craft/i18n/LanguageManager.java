package clanker.craft.i18n;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import clanker.craft.config.Config;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages language resources for ClankerCraft.
 * Loads language strings from JSON files and provides translations based on configured language.
 */
public final class LanguageManager {
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_LANGUAGE = "en";
    private static volatile String cachedLanguage;
    private static volatile Map<String, String> cachedTranslations;

    private LanguageManager() {}

    /**
     * Get a translated string for the given key.
     * Falls back to English if the key is not found in the configured language.
     * Falls back to the key itself if not found in any language.
     */
    public static String get(String key) {
        return get(key, key);
    }

    /**
     * Get a translated string for the given key with a custom fallback.
     */
    public static String get(String key, String fallback) {
        String currentLang = getConfiguredLanguage();
        Map<String, String> translations = getTranslations(currentLang);
        
        String value = translations.get(key);
        if (value != null) return value;
        
        // Try fallback to English if not already using English
        if (!DEFAULT_LANGUAGE.equals(currentLang)) {
            Map<String, String> englishTranslations = getTranslations(DEFAULT_LANGUAGE);
            value = englishTranslations.get(key);
            if (value != null) return value;
        }
        
        return fallback;
    }

    /**
     * Get a translated string and replace placeholders.
     * Placeholders are in the format {0}, {1}, etc.
     */
    public static String format(String key, Object... args) {
        String template = get(key);
        if (args == null || args.length == 0) return template;
        
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }

    /**
     * Get the currently configured language code.
     */
    public static String getConfiguredLanguage() {
        String lang = Config.get("CLANKER_LANGUAGE", "LANGUAGE");
        if (lang == null || lang.isBlank()) {
            lang = DEFAULT_LANGUAGE;
        }
        return lang.toLowerCase();
    }

    /**
     * Get the full language name for display purposes.
     */
    public static String getLanguageName() {
        String code = getConfiguredLanguage();
        return switch (code) {
            case "en" -> "English";
            case "es" -> "Español";
            case "fr" -> "Français";
            case "de" -> "Deutsch";
            case "it" -> "Italiano";
            case "pt" -> "Português";
            default -> code.toUpperCase();
        };
    }

    /**
     * Get the language instruction for the LLM.
     */
    public static String getLanguageInstruction() {
        String langName = getLanguageName();
        String code = getConfiguredLanguage();
        
        if (DEFAULT_LANGUAGE.equals(code)) {
            return "Always respond in English.";
        }
        
        return "Always respond in " + langName + ". All your responses must be in " + langName + ", never in English or any other language.";
    }

    /**
     * Load translations for a specific language.
     */
    private static Map<String, String> getTranslations(String langCode) {
        // Check cache
        if (langCode.equals(cachedLanguage) && cachedTranslations != null) {
            return cachedTranslations;
        }
        
        Map<String, String> translations = new HashMap<>();
        String resourcePath = "/assets/clankercraft/lang/" + langCode + ".json";
        
        try (InputStream in = LanguageManager.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder jsonBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        jsonBuilder.append(line);
                    }
                    JsonObject json = GSON.fromJson(jsonBuilder.toString(), JsonObject.class);
                    json.entrySet().forEach(entry -> translations.put(entry.getKey(), entry.getValue().getAsString()));
                }
            }
        } catch (Exception e) {
            // Silent failure - will use fallback
        }
        
        // Cache the result
        cachedLanguage = langCode;
        cachedTranslations = translations;
        
        return translations;
    }

    /**
     * Clear the cache. Useful when language settings change.
     */
    public static void clearCache() {
        cachedLanguage = null;
        cachedTranslations = null;
    }
}
