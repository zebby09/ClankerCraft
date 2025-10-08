package clanker.craft;

import clanker.craft.client.render.DiazJaquetRenderer;
import clanker.craft.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class ClankerCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Register DiazJaquet renderer (reuses vanilla Illusioner visuals)
		EntityRendererRegistry.register(ModEntities.DIAZ_JAQUET, DiazJaquetRenderer::new);
	}
}