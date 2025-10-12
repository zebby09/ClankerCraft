package clanker.craft.client;

import clanker.craft.network.TtsSpeakS2CPayload;
import clanker.craft.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.render.entity.IllusionerEntityRenderer;

public class ClankerCraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register payload type for CLIENTBOUND play
        PayloadTypeRegistry.playS2C().register(TtsSpeakS2CPayload.ID, TtsSpeakS2CPayload.CODEC);
        // Register typed receiver
        ClientPlayNetworking.registerGlobalReceiver(TtsSpeakS2CPayload.ID, (payload, context) -> {
            String text = payload.text();
            int entityId = payload.entityId();
            context.client().execute(() -> ClientTts.get().speakAsync(context.client(), text, entityId));
        });

        // Tick to cleanup OpenAL sources
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientTts.get().tick(client));

        // Register renderer for DiazJaquet entity (reuse vanilla Illusioner renderer)
        EntityRendererRegistry.register(ModEntities.DIAZ_JAQUET, IllusionerEntityRenderer::new);
    }
}
