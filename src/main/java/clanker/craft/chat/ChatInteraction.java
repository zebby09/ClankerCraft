package clanker.craft.chat;

import clanker.craft.entity.DiazJaquetEntity;
import clanker.craft.llm.LlmClient;
import clanker.craft.network.NetworkConstants;
import clanker.craft.network.TtsSpeakS2CPayload;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.*;

public final class ChatInteraction {
    private ChatInteraction() {}

    private static final String TRIGGER = "@diazjaquet"; // case-insensitive match
    private static final String BYE_TRIGGER = "@byebye"; // end conversation
    private static final double SEARCH_RANGE = 64.0; // blocks
    private static final double MOVE_SPEED = 1.2; // navigation speed

    // Conversation state per player
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final LlmClient LLM = new LlmClient();
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

    // Accessors for initializer logging
    public static boolean isLlmEnabled() { return LLM.isEnabled(); }
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

                    // Move mob to the player and set session
                    nearest.getNavigation().startMovingTo(player, MOVE_SPEED);
                    setSession(player, nearest);

                    int dist = (int) Math.sqrt(nearest.squaredDistanceTo(p));
                    player.sendMessage(Text.literal("DiazJaquet is coming to you (" + dist + "m). Conversing mode enabled. Type '@byebye' to end."));
                    return;
                }

                // End conversation
                if (lower.startsWith(BYE_TRIGGER)) {
                    Session s = SESSIONS.remove(player.getUuid());
                    if (s != null) {
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

                                // Also trigger client-side TTS playback using a custom payload
                                ServerPlayNetworking.send(player, new TtsSpeakS2CPayload(reply));
                            });
                        });

            } catch (Exception e) {
                // Be robust and avoid breaking chat
            }
        });
    }

    private static void setSession(ServerPlayerEntity player, DiazJaquetEntity mob) {
        Session s = new Session(mob.getUuid());
        // Optional: small system priming via initial history by adding a first model/user line
        s.appendModel("What's up?");
        SESSIONS.put(player.getUuid(), s);
        // Send opening message to the player
        player.sendMessage(net.minecraft.text.Text.literal("DiazJaquet: What's up?"));
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

        Session(UUID mobUuid) { this.mobUuid = mobUuid; }

        void appendUser(String text) {
            add("user: " + text);
        }

        void appendModel(String text) {
            add("model: " + text);
        }

        private void add(String turn) {
            // Keep last MAX_TURNS entries
            if (history.size() >= MAX_TURNS) history.removeFirst();
            history.addLast(turn);
        }
    }
}
