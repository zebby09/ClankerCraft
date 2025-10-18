package clanker.craft.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.World;

/**
 * The Clanker: a custom entity that mirrors the vanilla Illusioner but with neutral behavior.
 * We override goal initialization and attack to avoid any hostile actions (no raids, no attacking villagers).
 */
public class ClankerEntity extends CopperGolemEntity {
    public ClankerEntity(EntityType<? extends CopperGolemEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        // Remove any goals of the new mob that we don't want.
    }

    @Override
    protected SoundEvent getAmbientSound() {
        // Mute sounds while conversing
        return null;
    }
}
