package clanker.craft.registry;

import clanker.craft.ClankerCraft;
import clanker.craft.entity.DiazJaquetEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.IllusionerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class ModEntities {
    public static final RegistryKey<EntityType<?>> DIAZ_JAQUET_KEY = RegistryKey.of(
            RegistryKeys.ENTITY_TYPE, Identifier.of(ClankerCraft.MOD_ID, "diaz_jaquet")
    );

    public static final EntityType<DiazJaquetEntity> DIAZ_JAQUET = Registry.register(
            Registries.ENTITY_TYPE,
            DIAZ_JAQUET_KEY,
            EntityType.Builder
                    .create(DiazJaquetEntity::new, SpawnGroup.MONSTER)
                    .dimensions(0.6f, 1.95f)
                    .build(DIAZ_JAQUET_KEY)
    );

    public static void registerAttributes() {
        // Mirror illusioner base attributes
        FabricDefaultAttributeRegistry.register(DIAZ_JAQUET, IllusionerEntity.createIllusionerAttributes());
    }
}
