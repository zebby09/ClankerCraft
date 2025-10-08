package clanker.craft;

import clanker.craft.registry.ModEntities;
import clanker.craft.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClankerCraft implements ModInitializer {
	public static final String MOD_ID = "clankercraft";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Register entity attributes and items (spawn egg)
		ModEntities.registerAttributes();
		ModItems.register();

		LOGGER.info("ClankerCraft initialized: entities and items registered.");
	}
}