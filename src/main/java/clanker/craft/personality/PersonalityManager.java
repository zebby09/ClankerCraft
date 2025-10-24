package clanker.craft.personality;

import net.fabricmc.loader.api.FabricLoader;
import clanker.craft.config.Config;
import clanker.craft.i18n.LanguageManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class PersonalityManager {
    private PersonalityManager() {}

    private static final String DEFAULT_PERSONALITY_NAME = "clanker";
    private static volatile String cachedName;
    private static volatile String cachedText;

    public static String getActivePersonality() {
        try {
            String name = loadPersonalityName();
            if (name == null || name.isBlank()) name = DEFAULT_PERSONALITY_NAME;
            if (name.equals(cachedName) && cachedText != null) return cachedText;

            String text = loadPersonalityText(name);
            if (text == null || text.isBlank()) text = builtInFallback(name);
            
            // Append language instruction to ensure LLM responds in the correct language
            String languageInstruction = LanguageManager.getLanguageInstruction();
            text = text + " " + languageInstruction;
            
            cachedName = name;
            cachedText = text;
            return text;
        } catch (Throwable t) {
            return builtInFallback(DEFAULT_PERSONALITY_NAME);
        }
    }

    // Load personality name from the properties file
    private static String loadPersonalityName() {
        // Read from: clankercraft-llm.properties --> CLANKER_PERSONALITY
        Path cfgFile = FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties");
        if (Files.exists(cfgFile)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(cfgFile)) {
                props.load(in);
                String v = Config.personalityName();
                if (v == null) v = props.getProperty("CLANKER_PERSONALITY");
                if (v != null) return v.trim();
            } catch (IOException ignored) { }
        }
        return null;
    }

    // Load personality text from file (saved in assets)
    private static String loadPersonalityText(String name) {
        // 1) Try user-provided file: config/clankercraft/personalities/<Name>.txt
        Path folder = FabricLoader.getInstance().getConfigDir().resolve("clankercraft").resolve("personalities");
        Path file = folder.resolve(name + ".txt");
        if (Files.exists(file)) {
            try { return Files.readString(file, StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }
        // 2) Try bundled defaults: assets/clankercraft/personalities/<Name>.txt
        String cp = "/assets/clankercraft/personalities/" + name + ".txt";
        try (InputStream in = PersonalityManager.class.getResourceAsStream(cp)) {
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    return sb.toString();
                }
            }
        } catch (IOException ignored) { }
        return null;
    }

    private static String builtInFallback(String name) {
        // Default to Excited
        return "System instruction: You are Clanker, an excitable, upbeat companion. " +
                "Respond with enthusiasm, positivity, and helpful energy. Keep responses concise but lively.";
    }
}

