package clanker.craft.registry;

import clanker.craft.ClankerCraft;
import clanker.craft.entity.ClankerEntity;
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
    public static final RegistryKey<EntityType<?>> CLANKER_KEY = RegistryKey.of(
            RegistryKeys.ENTITY_TYPE, Identifier.of(ClankerCraft.MOD_ID, "clanker")
    );

    public static final EntityType<ClankerEntity> CLANKER = Registry.register(
            Registries.ENTITY_TYPE,
            CLANKER_KEY,
            EntityType.Builder
                    .create(ClankerEntity::new, SpawnGroup.MONSTER)
                    .dimensions(0.6f, 1.95f)
                    .build(CLANKER_KEY)
    );

    public static void registerAttributes() {
        // Mirror illusioner base attributes
        FabricDefaultAttributeRegistry.register(CLANKER, IllusionerEntity.createIllusionerAttributes());
    }
}
