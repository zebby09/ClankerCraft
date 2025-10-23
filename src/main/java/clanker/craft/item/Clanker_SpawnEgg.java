package clanker.craft.item;

import clanker.craft.registry.ModEntities;
import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;

/**
 * A spawn egg that uses vanilla SpawnEggItem rendering/behavior but spawns our Clanker entity.
 */
public class Clanker_SpawnEgg extends SpawnEggItem {
    public Clanker_SpawnEgg(Item.Settings settings) {
        super(settings);
    }

    @Override
    public EntityType<?> getEntityType(ItemStack stack) {
        return ModEntities.CLANKER;
    }
}

