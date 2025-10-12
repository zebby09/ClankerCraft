package clanker.craft.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.IllusionerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.World;

/**
 * DiazJaquet: a custom entity that mirrors the vanilla Illusioner.
 * We subclass IllusionerEntity so we inherit all behavior (AI goals, ranged attacks, invisibility, clones),
 * while having our own distinct EntityType for registration and a custom spawn egg.
 */
public class DiazJaquetEntity extends IllusionerEntity {
    public DiazJaquetEntity(EntityType<? extends IllusionerEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        // Mute default ambient grunts while conversing with TTS
        return null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        // Optionally mute hurt sound to avoid overlap with TTS; return null to silence
        return null;
    }

    @Override
    protected SoundEvent getDeathSound() {
        // Optionally mute death sound; return null to silence
        return null;
    }
}
