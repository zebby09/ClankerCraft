package clanker.craft.chat;

import clanker.craft.entity.DiazJaquetEntity;
import clanker.craft.llm.LLMClient;
import clanker.craft.network.TTSSpeakS2CPayload;
import clanker.craft.personality.PersonalityManager;
import clanker.craft.imagen.ImagenClient;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;


import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.decoration.painting.PaintingVariant;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.ComponentType;




import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Files;

public final class ChatInteraction {
    private ChatInteraction() {}

    private static final String TRIGGER = "@diazjaquet"; // case-insensitive match
    private static final String BYE_TRIGGER = "@byebye"; // end conversation
    private static final String PAINT_TRIGGER = "@makepainting"; // case-insensitive
    private static final String MUSIC_TRIGGER = "@makemusic"; // new: music generation via Lyria2
    private static final double SEARCH_RANGE = 256.0; // increased search range in blocks
    private static final double MOVE_SPEED = 1.2; // navigation speed
    private static final double ARRIVE_DISTANCE = 2.5; // when considered arrived to freeze
    private static final int PATH_REFRESH_TICKS = 20; // reissue path each second while approaching

    // Conversation state per player
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final LLMClient LLM = new LLMClient();
    private static final ImagenClient IMAGEN = new ImagenClient();
    private static final clanker.craft.music.Lyria2Client LYRIA = new clanker.craft.music.Lyria2Client();
    private static final ExecutorService EXEC = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "DiazJaquet-LLM");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static int tickCounter = 0;

    // Accessors for initializer logging
    public static boolean isLlmEnabled() { return LLM.isEnabled(); }
    public static boolean isImagenEnabled() { return IMAGEN.isEnabled(); }
    public static String llmModel() { return LLM.getModel(); }

    public static void register() {
        // Listen to server chat messages
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            try {
                String raw = null;
                try {
                    raw = message.getContent().getString();
                } catch (Throwable ignored) { }
                if (raw == null || raw.isEmpty()) return;

                // Ignore vanilla commands
                if (raw.startsWith("/")) return;

                String trimmed = raw.trim();
                String lower = trimmed.toLowerCase();

                ServerPlayerEntity player = sender;
                ServerWorld world = (ServerWorld) player.getEntityWorld();
                MinecraftServer server = world.getServer();

                // Start/refresh conversation and summon nearest
                if (lower.startsWith(TRIGGER)) {
                    Vec3d p = new Vec3d(player.getX(), player.getY(), player.getZ());
                    Box box = Box.from(p).expand(SEARCH_RANGE);
                    DiazJaquetEntity nearest = world.getEntitiesByClass(DiazJaquetEntity.class, box, e -> e.isAlive())
                            .stream()
                            .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
                            .orElse(null);

                    if (nearest == null) {
                        player.sendMessage(Text.literal("No DiazJaquet nearby (" + (int) SEARCH_RANGE + "m)."));
                        return;
                    }

                    // Unfreeze any previously selected mob for this player
                    Session existing = SESSIONS.get(player.getUuid());
                    if (existing != null) {
                        DiazJaquetEntity prev = findMobByUuid(world, existing.mobUuid);
                        if (prev != null) prev.setAiDisabled(false);
                    }

                    // Move mob to the player and set session
                    nearest.getNavigation().startMovingTo(player, MOVE_SPEED);
                    setSession(player, nearest);

                    // Replaced the old distance message with the greeting handled in setSession()
                    return;
                }

                // End conversation
                if (lower.startsWith(BYE_TRIGGER)) {
                    Session s = SESSIONS.remove(player.getUuid());
                    if (s != null) {
                        // Try to unfreeze the mob if still around
                        DiazJaquetEntity mob = findMobByUuid((ServerWorld) player.getEntityWorld(), s.mobUuid);
                        if (mob != null) {
                            mob.setAiDisabled(false);
                        }
                        player.sendMessage(Text.literal("DiazJaquet: bye! (conversation ended)"));
                    }
                    return;
                }

                // Route chat into active conversation if present
                Session session = SESSIONS.get(player.getUuid());
                if (session == null) return; // not conversing, ignore

                // Validate mob still exists/alive
                DiazJaquetEntity mob = findMobByUuid(world, session.mobUuid);
                if (mob == null || !mob.isAlive()) {
                    SESSIONS.remove(player.getUuid());
                    player.sendMessage(Text.literal("DiazJaquet is no longer here. Conversation ended."));
                    return;
                }

                // Handle @makepainting command within active conversation
                if (lower.startsWith(PAINT_TRIGGER)) {
                    // Extract prompt after the trigger
                    String prompt = trimmed.substring(PAINT_TRIGGER.length()).trim();
                    if (!IMAGEN.isEnabled()) {
                        String cfgPath = String.valueOf(FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties").toAbsolutePath());
                        player.sendMessage(Text.literal("Imagen not configured. Ensure GOOGLE_APPLICATION_CREDENTIALS, GCP_PROJECT_ID and GCP_LOCATION are set in " + cfgPath));
                        return;
                    }
                    if (prompt.isEmpty()) {
                        player.sendMessage(Text.literal("Usage: @MakePainting <prompt>"));
                        return;
                    }
                    if (session.busy) {
                        player.sendMessage(Text.literal("DiazJaquet is busy. Please wait..."));
                        return;
                    }
                    session.busy = true;
                    String startMsg = "Okay great! I'm creating a painting for '" + prompt + "'...";
                    player.sendMessage(Text.literal(startMsg));
                    // Speak start message via TTS
                    int startEntityId = mob.getId();
                    ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(startMsg, startEntityId));

                    CompletableFuture
                            .supplyAsync(() -> {
                                try {
                                    return IMAGEN.generateAndSave(prompt).toAbsolutePath().toString();
                                } catch (Exception e) {
                                    return "(error) " + e.getMessage();
                                }
                            }, EXEC)
                            .thenAcceptAsync(result -> {
                                server.execute(() -> {
                                    session.busy = false;
                                    if (result.startsWith("(error) ")) {
                                        player.sendMessage(Text.literal("DiazJaquet: failed to make painting: " + result.substring(8)));
                                    } else {
                                        player.sendMessage(Text.literal("DiazJaquet: saved painting to " + result));
                                        // Update the painting texture
                                        try {
                                            ImagenClient.updatePaintingTexture(java.nio.file.Path.of(result));
                                            player.sendMessage(Text.literal("DiazJaquet: painting texture updated! Press F3+T to reload and see it."));

                                            // Drop a painting item at the mob's location
                                            DiazJaquetEntity diaz = findMobByUuid(world, session.mobUuid);
                                            if (diaz != null && diaz.isAlive()) {

                                                // 1. create new itemstack
                                                ItemStack paintingStack = new ItemStack(Items.PAINTING);

                                                // 2. Find the PaintingVariant that matches the prompt:
                                                var registryManager = world.getRegistryManager();

                                                var paintingRegistry = registryManager.getOrThrow(RegistryKeys.PAINTING_VARIANT);

                                                var matchID = Identifier.of("match");

                                                var matchOptionalEntry = paintingRegistry.getEntry(matchID);

                                                RegistryEntry<PaintingVariant> matchEntry = matchOptionalEntry.orElseThrow(() -> new IllegalStateException("PaintingVariant not found: " + matchID));

                                                paintingStack.set((ComponentType) DataComponentTypes.PAINTING_VARIANT, matchEntry);

                                                // 3. finish
                                                String doneMsg = "I have finished painting. Here it is!";
                                                diaz.dropStack(world, paintingStack);
                                                player.sendMessage(Text.literal(doneMsg));
                                                // Speak success message via TTS
                                                ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(doneMsg, diaz.getId()));
                                            }
                                        } catch (Exception e) {
                                            player.sendMessage(Text.literal("DiazJaquet: saved but failed to update texture: " + e.getMessage()));
                                        }
                                    }
                                });
                            });
                    return; // do not pass this message to the LLM
                }

                // Handle @makemusic command within active conversation (first step: generate WAV into run/MusicSamples)
                if (lower.startsWith(MUSIC_TRIGGER)) {
                    String prompt = trimmed.substring(MUSIC_TRIGGER.length()).trim();
                    if (!LYRIA.isEnabled()) {
                        String cfgPath = String.valueOf(FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties").toAbsolutePath());
                        player.sendMessage(Text.literal("Lyria not configured. Ensure GOOGLE_APPLICATION_CREDENTIALS, GCP_PROJECT_ID and GCP_LOCATION are set in " + cfgPath));
                        return;
                    }
                    if (prompt.isEmpty()) {
                        player.sendMessage(Text.literal("Usage: @MakeMusic <prompt>"));
                        return;
                    }
                    if (session.busy) {
                        player.sendMessage(Text.literal("DiazJaquet is busy. Please wait..."));
                        return;
                    }
                    session.busy = true;
                    String startMsg = "Okay great! I'm composing some " + prompt + " Music!";
                    player.sendMessage(Text.literal(startMsg));
                    // Speak start message via TTS
                    int startEntityId = mob.getId();
                    ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(startMsg, startEntityId));

                    CompletableFuture
                            .supplyAsync(() -> {
                                try {
                                    java.nio.file.Path wav = LYRIA.generateAndSave(prompt);
                                    // Transcode to OGG Vorbis for Minecraft
                                    String name = wav.getFileName().toString();
                                    String base = name.endsWith(".wav") ? name.substring(0, name.length() - 4) : name;
                                    java.nio.file.Path ogg = wav.getParent().resolve(base + ".ogg");
                                    clanker.craft.music.FfmpegTranscoder.toOggVorbis(wav, ogg);
                                    String discId = "13"; // choose a vanilla disc to override
                                    clanker.craft.music.DiscOverridePackWriter.writeToBuildResources(discId, ogg);
                                    java.nio.file.Path packRoot = clanker.craft.music.DiscOverridePackWriter.writeToGeneratedPack(discId, ogg);
                                    // Delete the intermediate WAV to avoid saving both WAV and OGG in MusicSamples
                                    try { Files.deleteIfExists(wav); } catch (Exception ignored) {}
                                    return "OK|" + ogg.toAbsolutePath() + "|" + discId + "|" + packRoot.toAbsolutePath();
                                } catch (Exception e) {
                                    return "ERR|" + e.getMessage();
                                }
                            }, EXEC)
                            .thenAcceptAsync(result -> {
                                server.execute(() -> {
                                    session.busy = false;
                                    if (result.startsWith("ERR|")) {
                                        player.sendMessage(Text.literal("DiazJaquet: failed to prepare disc audio: " + result.substring(4)));
                                        player.sendMessage(Text.literal("Tip: ensure ffmpeg is installed and on PATH."));
                                    } else {
                                        String[] parts = result.split("\\|", 4);
                                        String oggPath = parts.length > 1 ? parts[1] : "";
                                        String discId = parts.length > 2 ? parts[2] : "13";
                                        String packRoot = parts.length > 3 ? parts[3] : "";
                                        // Inform only about the OGG since WAV was not kept
                                        player.sendMessage(Text.literal("DiazJaquet: composed and transcoded to OGG at " + oggPath));
                                        player.sendMessage(Text.literal("DiazJaquet: overridden disc '" + discId + "'. Press F3+T to reload."));
                                        player.sendMessage(Text.literal("If you don't hear it, enable the generated pack at " + packRoot));

                                        // Drop the corresponding music disc at the mob's location (simple: disc 13)
                                        DiazJaquetEntity diaz = findMobByUuid(world, session.mobUuid);
                                        if (diaz != null && diaz.isAlive()) {
                                            diaz.dropStack(world, new net.minecraft.item.ItemStack(net.minecraft.item.Items.MUSIC_DISC_13));
                                            String doneMsg = "I'm done composing! here's your music disc!";
                                            player.sendMessage(Text.literal(doneMsg));
                                            // Speak success message via TTS
                                            ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(doneMsg, diaz.getId()));
                                        }
                                    }
                                });
                            });
                    return; // do not pass message to LLM
                }

                // Append user turn and call LLM off-thread
                session.appendUser(trimmed);
                if (!LLM.isEnabled()) {
                    String cfgPath = String.valueOf(FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties").toAbsolutePath());
                    player.sendMessage(Text.literal("DiazJaquet (LLM disabled): Provide GOOGLE_AI_API_KEY via env var, JVM -DGOOGLE_AI_API_KEY, or create " + cfgPath + ". Then restart."));
                    return;
                }

                if (session.busy) {
                    player.sendMessage(Text.literal("DiazJaquet is thinking... please wait."));
                    return;
                }
                session.busy = true;

                CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return LLM.generate(new java.util.ArrayList<>(session.history), trimmed);
                            } catch (Exception e) {
                                return "(error) " + e.getMessage();
                            }
                        }, EXEC)
                        .thenAcceptAsync(reply -> {
                            // Back on server thread for game state/chat
                            server.execute(() -> {
                                session.appendModel(reply);
                                session.busy = false;
                                player.sendMessage(Text.literal("DiazJaquet: " + reply));

                                // Also trigger client-side TTS playback using a custom payload with entity position context
                                DiazJaquetEntity m = findMobByUuid(world, session.mobUuid);
                                int entityId = (m == null) ? -1 : m.getId();
                                ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(reply, entityId));
                            });
                        });

            } catch (Exception e) {
                // Be robust and avoid breaking chat
            }
        });

        // Periodic server tick: keep mobs walking to players and freeze upon arrival
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (SESSIONS.isEmpty()) return;

            for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
                UUID playerId = entry.getKey();
                Session s = entry.getValue();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (player == null) continue; // offline

                ServerWorld world = (ServerWorld) player.getEntityWorld();
                DiazJaquetEntity mob = findMobByUuid(world, s.mobUuid);
                if (mob == null || !mob.isAlive()) continue;

                double distSq = mob.squaredDistanceTo(player);
                if (s.awaitingFreeze) {
                    // Re-issue path every PATH_REFRESH_TICKS until arrived
                    if ((tickCounter - s.lastPathTick) >= PATH_REFRESH_TICKS) {
                        s.lastPathTick = tickCounter;
                        mob.getNavigation().startMovingTo(player, MOVE_SPEED);
                      }
                    if (distSq <= (ARRIVE_DISTANCE * ARRIVE_DISTANCE)) {
                        mob.setAiDisabled(true); // freeze in place
                        s.awaitingFreeze = false; // now frozen until @byebye
                        // Optionally, face the player (initial turn)
                        mob.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos());
                    }
                } else {
                    // After frozen, keep the mob looking at the player each tick so it feels alive
                    if (mob.isAiDisabled()) {
                        // Continuously face the player's eyes while conversing
                        mob.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos());
                    }
                }
            }
        });
    }

    private static void setSession(ServerPlayerEntity player, DiazJaquetEntity mob) {
        Session s = new Session(mob.getUuid());
        // Inject active personality as a system instruction to steer the LLM
        String persona = PersonalityManager.getActivePersonalityText();
        if (persona != null && !persona.isBlank()) {
            s.appendSystem(persona);
        }
        // mark that we should freeze the mob once it reaches the player
        s.awaitingFreeze = true;
        s.lastPathTick = tickCounter;
        // Send greeting as the initial model line and to the player, and speak it via TTS
        String greeting = "What's up my clanker!";
        s.appendModel(greeting);
        SESSIONS.put(player.getUuid(), s);
        player.sendMessage(Text.literal(greeting));
        ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(greeting, mob.getId()));
    }

    private static DiazJaquetEntity findMobByUuid(ServerWorld world, UUID uuid) {
        if (uuid == null) return null;
        Entity e = world.getEntity(uuid);
        if (e instanceof DiazJaquetEntity dj) return dj;
        return null;
    }

    private static final class Session {
        final UUID mobUuid;
        final Deque<String> history = new ArrayDeque<>(); // keep as ring buffer
        private static final int MAX_TURNS = 20; // max entries in history deque
        volatile boolean busy = false;
        // freeze-on-arrival state
        volatile boolean awaitingFreeze = false;
        int lastPathTick = 0;

        Session(UUID mobUuid) { this.mobUuid = mobUuid; }

        void appendUser(String text) { add("user: " + text); }
        void appendModel(String text) { add("model: " + text); }
        void appendSystem(String text) { add("system: " + text); }

        private void add(String turn) {
            // Keep last MAX_TURNS entries
            if (history.size() >= MAX_TURNS) history.removeFirst();
            history.addLast(turn);
        }
    }
}
