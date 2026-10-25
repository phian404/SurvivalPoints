package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Регистрация типов сущностей.
 *
 * <p>Единственное место, где регистрируется страж. Раньше такая же регистрация
 * была ещё и в {@code SurvivalPointsMod} — под тем же именем
 * {@code survivalpoints:base_guardian}. Из-за этого выживал только один из
 * вариантов, а какой именно — зависело от порядка загрузки классов.
 *
 * <p>Размер 0.6 x 1.8 совпадает с PLAYER-моделью, которая используется
 * в рендерере стража.
 */
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SurvivalPointsMod.MOD_ID);

    public static final RegistryObject<EntityType<BaseGuardianEntity>> BASE_GUARDIAN =
            ENTITY_TYPES.register("base_guardian", () ->
                    EntityType.Builder.of(BaseGuardianEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(8)
                            .build("survivalpoints:base_guardian")
            );

    public static final RegistryObject<ForgeSpawnEggItem> BASE_GUARDIAN_SPAWN_EGG =
            ModBlocks.ITEMS.register("base_guardian_spawn_egg", () ->
                    new ForgeSpawnEggItem(BASE_GUARDIAN, 0x3A3A3A, 0xF28C28, new Item.Properties()));

    public static ModelLayerLocation BASE_GUARDIAN_LAYER;
}
