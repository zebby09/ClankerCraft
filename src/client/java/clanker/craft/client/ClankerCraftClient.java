package clanker.craft.client;

import clanker.craft.network.TTSSpeakS2CPayload;
import clanker.craft.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.render.entity.IllusionerEntityRenderer;


public class ClankerCraftClient implements ClientModInitializer {

    // Client-side only initialization code should go here
    @Override
    public void onInitializeClient() {

        // Register a payload type for client-bound play
        PayloadTypeRegistry.playS2C().register(TTSSpeakS2CPayload.ID, TTSSpeakS2CPayload.CODEC);

        // Register typed receiver
        ClientPlayNetworking.registerGlobalReceiver(TTSSpeakS2CPayload.ID, (payload, context) -> {
            TTSSpeakS2CPayload p = (TTSSpeakS2CPayload) payload;
            String text = p.text();
            int entityId = p.entityId();
            context.client().execute(() -> ClientTTS.get().speakAsync(context.client(), text, entityId));
        });

        // Tick to cleanup OpenAL sources
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientTTS.get().tick(client));

        // Register renderer for DiazJaquet entity (reuse vanilla Illusioner renderer)
        EntityRendererRegistry.register(ModEntities.DIAZ_JAQUET, IllusionerEntityRenderer::new);
    }
}
