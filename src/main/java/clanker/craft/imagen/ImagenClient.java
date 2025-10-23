package clanker.craft.imagen;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import clanker.craft.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.Properties;

/**
 * Client to call Vertex AI Imagen text-to-image and save the resulting image locally.
 */
public final class ImagenClient {
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Logger LOGGER = LoggerFactory.getLogger("ClankerCraft-Imagen");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String projectId;
    private final String location;
    private final String model;

    private final GoogleCredentials credentials;

    public ImagenClient() {
        this.projectId = Config.gcpProjectId();
        String loc = Config.gcpLocationOrDefault("us-central1");
        this.location = (loc == null || loc.isBlank()) ? "us-central1" : loc.trim();
        String m = Config.imagenModelOrDefault("imagegeneration");
        this.model = (m == null || m.isBlank()) ? "imagegeneration" : m.trim();
        this.credentials = loadCredentials();
        // Ensure output dir exists early to fail fast on permissions
        try { Files.createDirectories(getOutputDir()); } catch (IOException ignored) {}
    }

    public boolean isEnabled() {
        return credentials != null && projectId != null && !projectId.isBlank();
    }

    public String getProjectId() { return projectId; }
    public String getLocation() { return location; }
    public String getModel() { return model; }

    /**
     * Generates an image with the given prompt and saves it as a PNG file. Returns the saved file path.
     */
    public Path generateAndSave(String prompt) throws Exception {
        if (!isEnabled()) throw new IllegalStateException("Imagen is not configured");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt is empty");

        String token = getAccessToken();
        if (token == null || token.isBlank()) throw new IllegalStateException("Failed to obtain Google access token");

        // Build possible model identifiers (try raw, then versioned) and endpoints (:predict first, then :generate)
        String baseModel = model;
        String[] modelVariants;
        if (baseModel.contains("@")) {
            modelVariants = new String[]{baseModel};
        } else {
            modelVariants = new String[]{baseModel, baseModel + "@002", baseModel + "@001"}; // common Imagen revision suffixes
        }
        String[] endpoints = new String[]{":predict", ":generate"};

        byte[] imageBytes = null;
        int lastStatus = -1; String lastBody = null; boolean any2xx = false; String usedModel = null; String usedEndpoint = null;
        for (String mv : modelVariants) {
            for (String ep : endpoints) {
                URI uri = URI.create("https://" + location + "-aiplatform.googleapis.com/v1/projects/" + projectId + "/locations/" + location + "/publishers/google/models/" + mv + ep);
                JsonObject body = new JsonObject();
                JsonArray instances = new JsonArray();
                JsonObject instance = new JsonObject();
                instance.addProperty("prompt", prompt);
                instances.add(instance);
                body.add("instances", instances);
                JsonObject params = new JsonObject();
                params.addProperty("sampleCount", 1);
                body.add("parameters", params);
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp;
                try {
                    resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    lastBody = e.getMessage();
                    continue; // try next
                }
                lastStatus = resp.statusCode();
                lastBody = resp.body();
                if (resp.statusCode() / 100 == 2) {
                    any2xx = true;
                    imageBytes = extractImageBytes(resp.body());
                    usedModel = mv; usedEndpoint = ep;
                    if (imageBytes != null && imageBytes.length > 0) {
                        break; // success
                    }
                } else if (resp.statusCode() == 404) {
                    // try next variant
                    continue;
                } else {
                    // Non-404 error; still allow trying next variant but record
                    continue;
                }
            }
            if (imageBytes != null && imageBytes.length > 0) break;
        }

        // If still not found and lastStatus was 404, attempt well-known Imagen model identifiers as fallback
        if ((imageBytes == null || imageBytes.length > 0) && lastStatus == 404) {
            String[] fallbackModels = new String[]{
                    "imagen-3.0-generate-001",
                    "imagen-3.0-fast-generate-001",
                    "imagen-2.0-generate-001",
                    "imagen-2.0-fast-generate-001",
                    "imagegeneration@002",
                    "imagegeneration@001"
            };
            for (String fm : fallbackModels) {
                for (String ep : endpoints) {
                    URI uri = URI.create("https://" + location + "-aiplatform.googleapis.com/v1/projects/" + projectId + "/locations/" + location + "/publishers/google/models/" + fm + ep);
                    JsonObject body = new JsonObject();
                    JsonArray instances = new JsonArray();
                    JsonObject instance = new JsonObject();
                    instance.addProperty("prompt", prompt);
                    instances.add(instance);
                    body.add("instances", instances);
                    JsonObject params = new JsonObject();
                    params.addProperty("sampleCount", 1);
                    body.add("parameters", params);
                    HttpRequest req = HttpRequest.newBuilder(uri)
                            .timeout(TIMEOUT)
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> resp;
                    try { resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
                    catch (Exception e) { lastBody = e.getMessage(); continue; }
                    lastStatus = resp.statusCode();
                    lastBody = resp.body();
                    if (resp.statusCode() / 100 == 2) {
                        any2xx = true;
                        imageBytes = extractImageBytes(resp.body());
                        usedModel = fm; usedEndpoint = ep;
                        if (imageBytes != null && imageBytes.length > 0) break;
                    } else if (resp.statusCode() == 404) {
                        continue;
                    }
                }
                if (imageBytes != null && imageBytes.length > 0) break;
            }
        }

        if (imageBytes == null || imageBytes.length == 0) {
            if (any2xx) {
                throw new RuntimeException("Imagen response OK but contained no image data (last modelAttempt=" + usedModel + usedEndpoint + ")");
            }
            throw new RuntimeException("Imagen request failed (lastStatus=" + lastStatus + "): " + (lastBody == null ? "(no body)" : truncate(lastBody, 500)));
        }

        Path outDir = getOutputDir();
        String safe = slug(prompt);
        String filename = "painting-" + TS.format(LocalDateTime.now()) + (safe.isEmpty() ? "" : ("-" + safe)) + ".png";
        Path file = outDir.resolve(filename);
        Files.write(file, imageBytes);
        try {
            LOGGER.info("Imagen generated (model={}, endpoint={}, bytes={}, prompt='{}', file={})", usedModel, usedEndpoint, imageBytes.length, truncate(prompt, 120), file.toAbsolutePath());
        } catch (Throwable ignored) {}
        return file;
    }

    private static String readFromConfig(String key) {
        // Deprecated: use Config helper

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

    private String getAccessToken() throws IOException {
        if (credentials == null) return null;
        synchronized (credentials) {
            AccessToken t = credentials.getAccessToken();
            if (t == null || t.getTokenValue() == null || t.getExpirationTime() == null || t.getExpirationTime().getTime() - System.currentTimeMillis() < 60_000) {
                credentials.refreshIfExpired();
                t = credentials.getAccessToken();
                if (t == null || t.getTokenValue() == null) {
                    // some environments require explicit refresh
                    t = credentials.refreshAccessToken();
                }
            }
            return (t == null) ? null : t.getTokenValue();
        }
    }

    private static byte[] extractImageBytes(String body) {
        try {
            JsonObject json = GSON.fromJson(body, JsonObject.class);
            // Try predictions[0].bytesBase64Encoded
            if (json.has("predictions")) {
                JsonArray preds = json.getAsJsonArray("predictions");
                if (preds.size() > 0) {
                    JsonObject p0 = preds.get(0).getAsJsonObject();
                    if (p0.has("bytesBase64Encoded")) {
                        String b64 = p0.get("bytesBase64Encoded").getAsString();
                        return Base64.getDecoder().decode(b64);
                    }
                    // Try generatedImages[0].bytesBase64Encoded
                    if (p0.has("generatedImages")) {
                        JsonArray arr = p0.getAsJsonArray("generatedImages");
                        if (arr.size() > 0) {
                            JsonObject g0 = arr.get(0).getAsJsonObject();
                            if (g0.has("bytesBase64Encoded")) {
                                String b64 = g0.get("bytesBase64Encoded").getAsString();
                                return Base64.getDecoder().decode(b64);
                            }
                        }
                    }
                    // Some responses embed images in "images" array with base64 data
                    if (p0.has("images") && p0.get("images").isJsonArray()) {
                        JsonArray imgs = p0.getAsJsonArray("images");
                        if (imgs.size() > 0) {
                            JsonElement first = imgs.get(0);
                            if (first.isJsonObject()) {
                                JsonObject im0 = first.getAsJsonObject();
                                if (im0.has("bytesBase64Encoded")) {
                                    String b64 = im0.get("bytesBase64Encoded").getAsString();
                                    return Base64.getDecoder().decode(b64);
                                }
                                if (im0.has("base64Data")) {
                                    String b64 = im0.get("base64Data").getAsString();
                                    return Base64.getDecoder().decode(b64);
                                }
                            } else if (first.isJsonPrimitive()) {
                                // If image is returned directly as a base64 string
                                String b64 = first.getAsString();
                                return Base64.getDecoder().decode(b64);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static Path getOutputDir() {
        return FabricLoader.getInstance().getGameDir().resolve("PaintingImages");
    }

    private static String slug(String s) {
        String cleaned = s.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        cleaned = cleaned.replaceAll("-+", "-");
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40);
        return cleaned.replaceAll("^-|-$", "");
    }

    private static String truncate(String s, int max) { return (s.length() <= max) ? s : s.substring(0, max) + "..."; }

    /**
     * Updates the pointer.png painting texture with the given image file, resized to 64x64.
     */
    public static void updatePaintingTexture(Path sourceImagePath) {
        try {
            // Read the source image
            BufferedImage sourceImage = ImageIO.read(sourceImagePath.toFile());
            if (sourceImage == null) {
                LOGGER.warn("Failed to read image from {}", sourceImagePath);
                return;
            }

            // Resize to 64x64 (standard size for 4x4 Minecraft paintings)
            BufferedImage resized = resizeImage(sourceImage, 64, 64);

            Path gameDir = FabricLoader.getInstance().getGameDir();

            // 1. Update source code resources (for next rebuild)
            Path srcResourcesDir = gameDir.getParent()
                    .resolve("src")
                    .resolve("main")
                    .resolve("resources")
                    .resolve("assets")
                    .resolve("minecraft")
                    .resolve("textures")
                    .resolve("painting");

            Files.createDirectories(srcResourcesDir);
            Path srcTargetPath = srcResourcesDir.resolve("pointer.png");
            ImageIO.write(resized, "PNG", srcTargetPath.toFile());
            LOGGER.info("Updated source painting texture: {}", srcTargetPath.toAbsolutePath());

            // 2. Update runtime build output (for immediate F3+T reload)
            Path buildResourcesDir = gameDir.getParent()
                    .resolve("build")
                    .resolve("resources")
                    .resolve("main")
                    .resolve("assets")
                    .resolve("minecraft")
                    .resolve("textures")
                    .resolve("painting");

            if (Files.exists(buildResourcesDir.getParent().getParent().getParent())) {
                Files.createDirectories(buildResourcesDir);
                Path buildTargetPath = buildResourcesDir.resolve("pointer.png");
                ImageIO.write(resized, "PNG", buildTargetPath.toFile());
                LOGGER.info("Updated build painting texture: {}", buildTargetPath.toAbsolutePath());
                LOGGER.info("Press F3+T in-game to reload and see the new painting!");
            } else {
                LOGGER.warn("Build directory not found, only source was updated");
            }

        } catch (Exception e) {
            LOGGER.error("Failed to update painting texture from {}: {}", sourceImagePath, e.getMessage());
        }
    }


    private static BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resizedImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return resizedImage;
    }
}
