package clanker.craft.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.IllusionerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.world.World;

/**
 * DiazJaquet: a custom entity that mirrors the vanilla Illusioner.
 * We subclass IllusionerEntity so we inherit all behavior,
 * while having our own distinct EntityType for registration and a custom spawn egg.
 */
public class DiazJaquetEntity extends IllusionerEntity {
    public DiazJaquetEntity(EntityType<? extends IllusionerEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        // Mute grunts while conversing with TTS
        return null;
    }
}
