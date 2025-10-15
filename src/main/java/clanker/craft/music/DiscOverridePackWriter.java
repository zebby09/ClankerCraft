package clanker.craft.music;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DiscOverridePackWriter {
    private DiscOverridePackWriter() {}

    /**
     * Writes the given OGG file into a generated resource pack that overrides a vanilla disc id
     * (e.g., "13", "cat", etc.) at assets/minecraft/sounds/records/<discId>.ogg and creates pack.mcmeta.
     * Returns the pack root.
     */
    public static Path writeToGeneratedPack(String discId, Path oggFile) throws IOException {
        if (discId == null || discId.isBlank()) throw new IllegalArgumentException("discId empty");
        if (oggFile == null || !Files.exists(oggFile)) throw new IOException("OGG not found: " + oggFile);
        Path packRoot = FabricLoader.getInstance().getGameDir().resolve("generated-pack");
        Path target = packRoot.resolve("assets/minecraft/sounds/records/" + discId + ".ogg");
        Files.createDirectories(target.getParent());
        Files.copy(oggFile, target, StandardCopyOption.REPLACE_EXISTING);
        // Minimal pack metadata
        Path mcmeta = packRoot.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            String meta = "{\n" +
                    "  \"pack\": { \"pack_format\": 34, \"description\": \"ClankerCraft generated music overrides\" }\n" +
                    "}\n";
            Files.writeString(mcmeta, meta);
        }
        return packRoot;
    }

    /**
     * Also drop the OGG into the dev build resources so that F3+T will pick it up without toggling packs.
     * Returns the built path if successful.
     */
    public static Path writeToBuildResources(String discId, Path oggFile) throws IOException {
        if (discId == null || discId.isBlank()) throw new IllegalArgumentException("discId empty");
        if (oggFile == null || !Files.exists(oggFile)) throw new IOException("OGG not found: " + oggFile);
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path buildResourcesDir = gameDir.getParent()
                .resolve("build")
                .resolve("resources")
                .resolve("main")
                .resolve("assets")
                .resolve("minecraft")
                .resolve("sounds")
                .resolve("records");
        if (!Files.exists(buildResourcesDir.getParent().getParent().getParent())) {
            // Build dir not present; skip silently
            return null;
        }
        Files.createDirectories(buildResourcesDir);
        Path target = buildResourcesDir.resolve(discId + ".ogg");
        Files.copy(oggFile, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}

