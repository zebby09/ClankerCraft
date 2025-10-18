package clanker.craft;

import clanker.craft.registry.ModEntities;
import clanker.craft.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import clanker.craft.chat.ChatInteraction;

public class ClankerCraft implements ModInitializer {
	public static final String MOD_ID = "clankercraft";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Register entities, items, and chat interaction
		ModEntities.registerAttributes();
		ModItems.register();
		ChatInteraction.register();

		if (ChatInteraction.isLlmEnabled()) {
			LOGGER.info("LLM enabled (model={}).", ChatInteraction.llmModel());
		} else {
			LOGGER.warn("LLM disabled. Provide API key via config file.");
		}

		if (ChatInteraction.isImagenEnabled()) {
			LOGGER.info("Imagen enabled (project configured). Use @MakePainting <prompt> during a conversation.");
		} else {
			LOGGER.warn("!!! Imagen disabled. Set credentials in config.");
		}

		LOGGER.info("ClankerCraft initialized: entities, items, and chat interactions registered.");
	}
}