package clanker.craft.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.IllusionerEntity;
import net.minecraft.entity.passive.CopperGolemEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.World;

/**
 * DiazJaquet: a custom entity that mirrors the vanilla Illusioner but with neutral behavior.
 * We override goal initialization and attack to avoid any hostile actions (no raids, no attacking villagers).
 */
public class DiazJaquetEntity extends CopperGolemEntity {
    public DiazJaquetEntity(EntityType<? extends CopperGolemEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected void initGoals() {
        // Intentionally do not call super.initGoals();
        // This prevents adding any of the default Illusioner/Illager hostile goals (attacks, spells, raid logic).
        // We keep this entity passive; movement is commanded externally via navigation when conversing.
    }


    @Override
    protected SoundEvent getAmbientSound() {
        // Mute grunts while conversing with TTS
        return null;
    }
}
