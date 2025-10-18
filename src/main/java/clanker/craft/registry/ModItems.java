package clanker.craft.registry;

import clanker.craft.ClankerCraft;
import clanker.craft.item.Clanker_SpawnEgg;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class ModItems {
    public static final RegistryKey<Item> CLANKER_SPAWNER_KEY = RegistryKey.of(
            RegistryKeys.ITEM, Identifier.of(ClankerCraft.MOD_ID, "clanker_spawner")
    );

    public static final Item CLANKER_SPAWNER = Registry.register(
            Registries.ITEM,
            CLANKER_SPAWNER_KEY,
            new Clanker_SpawnEgg(new Item.Settings().registryKey(CLANKER_SPAWNER_KEY))
    );

    public static void register() {
        // Ensure it appears with all other spawn eggs in Creative
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> entries.add(CLANKER_SPAWNER));
    }
}
