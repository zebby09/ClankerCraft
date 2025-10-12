package clanker.craft.item;

import clanker.craft.registry.ModEntities;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;

/**
 * A spawn egg that uses vanilla SpawnEggItem rendering/behavior but spawns our DiazJaquet entity.
 * This avoids custom spawning logic and keeps visuals identical to other spawn eggs.
 */
public class DiazJaquet_SpawnEgg extends SpawnEggItem {
    public DiazJaquet_SpawnEgg(Item.Settings settings) {
        super(settings);
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return ModEntities.DIAZ_JAQUET;
    }
}

