package clanker.craft.chat;

// CLIENTS
import clanker.craft.llm.LLMClient;
import clanker.craft.imagen.ImagenClient;
import clanker.craft.music.Lyria2Client;

// CLANKER ENTITY
import clanker.craft.entity.ClankerEntity;
import clanker.craft.personality.PersonalityManager;

// I18N
import clanker.craft.i18n.LanguageManager;

// NETWORKING
import clanker.craft.network.TTSSpeakS2CPayload;

// MINECRAFT
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.item.Items;
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
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.component.ComponentType;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.Files;


public final class ChatInteraction {
    private ChatInteraction() {}

    private static final String TRIGGER = "@clanker"; // case-insensitive match
    private static final String BYE_TRIGGER = "@bye"; // end conversation
    private static final String PAINT_TRIGGER = "@makepainting"; // case-insensitive
    private static final String MUSIC_TRIGGER = "@makemusic"; // new: music generation via Lyria2
    private static final double SEARCH_RANGE = 256.0; // increased search range in blocks
    private static final double MOVE_SPEED = 1; // navigation speed
    private static final double ARRIVE_DISTANCE = 2.5; // when considered arrived to freeze
    private static final int PATH_REFRESH_TICKS = 20; // reissue path each second while approaching

    // Conversation state per player
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final LLMClient LLM = new LLMClient();
    private static final ImagenClient IMAGEN = new ImagenClient();
    private static final clanker.craft.music.Lyria2Client LYRIA = new Lyria2Client();
    private static final ExecutorService EXEC = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "Clanker-LLM"); t.setDaemon(true); return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static int tickCounter = 0;

    // Accessors for initializer logging
    public static boolean isLlmEnabled() { return LLM.isEnabled(); }
    public static boolean isImagenEnabled() { return IMAGEN.isEnabled(); }
    public static String llmModel() { return LLM.getModel(); }


    // MAIN FUNCTIONALITY: Register chat listener --> Listen to server chat messages
    public static void register() {
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

                // 1) START conversation
                if (lower.startsWith(TRIGGER)) {
                    Vec3d p = new Vec3d(player.getX(), player.getY(), player.getZ());
                    Box box = Box.from(p).expand(SEARCH_RANGE);
                    ClankerEntity nearest = world.getEntitiesByClass(ClankerEntity.class, box, e -> e.isAlive())
                            .stream()
                            .min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p)))
                            .orElse(null);

                    if (nearest == null) {
                        player.sendMessage(Text.literal(LanguageManager.get("clanker.no_nearby")));
                        return;
                    }

                    // Unfreeze any previously selected mob for this player
                    Session existing = SESSIONS.get(player.getUuid());
                    if (existing != null) {
                        ClankerEntity prev = findMobByUuid(world, existing.mobUuid);
                        if (prev != null) prev.setAiDisabled(false);
                    }

                    // Move mob to the player and set session
                    nearest.getNavigation().startMovingTo(player, MOVE_SPEED);
                    setSession(player, nearest);

                    return;
                }

                // 2) END conversation
                if (lower.startsWith(BYE_TRIGGER)) {
                    Session s = SESSIONS.remove(player.getUuid());
                    if (s != null) {
                        // Try to unfreeze the mob if still around
                        ClankerEntity mob = findMobByUuid((ServerWorld) player.getEntityWorld(), s.mobUuid);
                        if (mob != null) {
                            mob.setAiDisabled(false);
                        }
                        // Speak bye message via TTS
                        String byeMsg = LanguageManager.get("clanker.farewell");
                        player.sendMessage(Text.literal(byeMsg));
                        int startEntityId = mob.getId();
                        ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(byeMsg, startEntityId));
                    }
                    return;
                }

                // Route chat into active conversation if present
                Session session = SESSIONS.get(player.getUuid());
                if (session == null) return; // not conversing, ignore

                // Validate mob still exists/alive
                ClankerEntity mob = findMobByUuid(world, session.mobUuid);
                if (mob == null || !mob.isAlive()) {
                    SESSIONS.remove(player.getUuid());
                    player.sendMessage(Text.literal(LanguageManager.get("clanker.gone")));
                    return;
                }


                // A) PAINTING GENERATION
                if (lower.startsWith(PAINT_TRIGGER)) {
                    // Extract prompt after the trigger
                    String prompt = trimmed.substring(PAINT_TRIGGER.length()).trim();
                    if (!IMAGEN.isEnabled()) {
                        String cfgPath = String.valueOf(FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties").toAbsolutePath());
                        player.sendMessage(Text.literal(LanguageManager.format("clanker.config.imagen_not_configured", cfgPath)));
                        return;
                    }
                    if (prompt.isEmpty()) {
                        player.sendMessage(Text.literal(LanguageManager.get("clanker.painting.prompt_required")));
                        return;
                    }
                    if (session.busy) {
                        player.sendMessage(Text.literal(LanguageManager.get("clanker.busy")));
                        return;
                    }
                    session.busy = true;
                    // Speak start message via TTS
                    String startMsg = LanguageManager.format("clanker.painting.start", prompt);
                    player.sendMessage(Text.literal(startMsg));
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
                                        player.sendMessage(Text.literal(LanguageManager.format("clanker.painting.failed", result.substring(8))));
                                    } else {
                                        try {
                                            ImagenClient.updatePaintingTexture(java.nio.file.Path.of(result));
                                            player.sendMessage(Text.literal(LanguageManager.get("clanker.painting.reload_textures")));

                                            // Drop a painting item at the mob's location
                                            ClankerEntity clanker = findMobByUuid(world, session.mobUuid);
                                            if (clanker != null && clanker.isAlive()) {

                                                // 1. Create a new itemstack
                                                ItemStack paintingStack = new ItemStack(Items.PAINTING);

                                                // 2. Find the correct painting variant
                                                var registryManager = world.getRegistryManager();
                                                var paintingRegistry = registryManager.getOrThrow(RegistryKeys.PAINTING_VARIANT);
                                                var matchID = Identifier.of("pointer");
                                                var matchOptionalEntry = paintingRegistry.getEntry(matchID);
                                                RegistryEntry<PaintingVariant> matchEntry = matchOptionalEntry.orElseThrow(() -> new IllegalStateException("PaintingVariant not found: " + matchID));
                                                paintingStack.set((ComponentType) DataComponentTypes.PAINTING_VARIANT, matchEntry);

                                                // 3. Finish and drop painting for the player
                                                String doneMsg = LanguageManager.get("clanker.painting.done");
                                                // Speak success message via TTS
                                                clanker.dropStack(world, paintingStack);
                                                player.sendMessage(Text.literal(doneMsg));
                                                ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(doneMsg, clanker.getId()));
                                            }
                                        } catch (Exception e) {
                                            player.sendMessage(Text.literal(LanguageManager.format("clanker.painting.texture_failed", e.getMessage())));
                                        }
                                    }
                                });
                            });
                    return;
                }


                // B) MUSIC DISC GENERATION
                if (lower.startsWith(MUSIC_TRIGGER)) {
                    String prompt = trimmed.substring(MUSIC_TRIGGER.length()).trim();
                    if (!LYRIA.isEnabled()) {
                        String cfgPath = String.valueOf(FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties").toAbsolutePath());
                        player.sendMessage(Text.literal(LanguageManager.format("clanker.config.lyria_not_configured", cfgPath)));
                        return;
                    }
                    if (prompt.isEmpty()) {
                        player.sendMessage(Text.literal(LanguageManager.get("clanker.music.prompt_required")));
                        return;
                    }
                    if (session.busy) {
                        player.sendMessage(Text.literal(LanguageManager.get("clanker.busy")));
                        return;
                    }
                    session.busy = true;

                    // Speak start message via TTS
                    String startMsg = LanguageManager.format("clanker.music.start", prompt);
                    player.sendMessage(Text.literal(startMsg));
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
                                        player.sendMessage(Text.literal(LanguageManager.get("clanker.music.failed") + result.substring(4)));
                                    } else {
                                        String[] parts = result.split("\\|", 4);
                                        String oggPath = parts.length > 1 ? parts[1] : "";
                                        String discId = parts.length > 2 ? parts[2] : "13";
                                        String packRoot = parts.length > 3 ? parts[3] : "";

                                        // Drop the corresponding music disc at the mob's location (disc 13)
                                        ClankerEntity clanker = findMobByUuid(world, session.mobUuid);
                                        if (clanker != null && clanker.isAlive()) {
                                            clanker.dropStack(world, new net.minecraft.item.ItemStack(net.minecraft.item.Items.MUSIC_DISC_13));
                                            // Speak success message via TTS
                                            String doneMsg = LanguageManager.get("clanker.music.done");
                                            player.sendMessage(Text.literal(doneMsg));
                                            ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(doneMsg, clanker.getId()));
                                        }
                                    }
                                });
                            });
                    return;
                }


                // C) REGULAR CHAT MESSAGE --> LLM RESPONSE + TTS
                session.appendUser(trimmed);
                if (!LLM.isEnabled()) {
                    String cfgPath = String.valueOf(FabricLoader.getInstance().getConfigDir().resolve("clankercraft-llm.properties").toAbsolutePath());
                    player.sendMessage(Text.literal(LanguageManager.format("clanker.config.llm_not_configured", cfgPath)));
                    return;
                }

                if (session.busy) {
                    player.sendMessage(Text.literal(LanguageManager.get("clanker.thinking")));
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
                                player.sendMessage(Text.literal(LanguageManager.get("clanker.response_prefix") + reply));

                                // Also trigger client-side TTS playback using a custom payload with entity position context
                                ClankerEntity m = findMobByUuid(world, session.mobUuid);
                                int entityId = (m == null) ? -1 : m.getId();
                                ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(reply, entityId));
                            });
                        });

            } catch (Exception e) {
                // handle exceptional case
            }
        });


        // WALKING FIX: use server tick to keep mobs walking to players and freeze upon arrival
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (SESSIONS.isEmpty()) return;

            for (Map.Entry<UUID, Session> entry : SESSIONS.entrySet()) {
                UUID playerId = entry.getKey();
                Session s = entry.getValue();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (player == null) continue; // offline

                ServerWorld world = (ServerWorld) player.getEntityWorld();
                ClankerEntity mob = findMobByUuid(world, s.mobUuid);
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
                        s.awaitingFreeze = false; // now frozen until @bye
                        mob.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos());
                    }
                } else {
                    // After frozen, keep the mob looking at the player each tick
                    if (mob.isAiDisabled()) {
                        mob.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos());
                    }
                }
            }
        });
    }


    // Create and store a new session
    private static void setSession(ServerPlayerEntity player, ClankerEntity mob) {
        Session s = new Session(mob.getUuid());
        // Inject active personality as a system instruction to steer the LLM
        String persona = PersonalityManager.getActivePersonality();
        if (persona != null && !persona.isBlank()) {
            s.appendSystem(persona);
        }
        // mark that we should freeze the mob once it reaches the player
        s.awaitingFreeze = true;
        s.lastPathTick = tickCounter;
        // Send greeting as the initial model line and to the player, and speak it via TTS
        String greeting = LanguageManager.get("clanker.greeting");
        s.appendModel(greeting);
        SESSIONS.put(player.getUuid(), s);
        player.sendMessage(Text.literal(greeting));
        ServerPlayNetworking.send(player, new TTSSpeakS2CPayload(greeting, mob.getId()));
    }

    private static ClankerEntity findMobByUuid(ServerWorld world, UUID uuid) {
        if (uuid == null) return null;
        Entity e = world.getEntity(uuid);
        if (e instanceof ClankerEntity dj) return dj;
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
