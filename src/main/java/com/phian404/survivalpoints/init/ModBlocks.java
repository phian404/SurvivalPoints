package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.camp.block.CampRadioBlock;
import com.phian404.survivalpoints.camp.blockentity.CampRadioBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, SurvivalPointsMod.MOD_ID);

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_RADIO =
            BLOCKS.register("camp_radio", () -> new CampRadioBlock());

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SurvivalPointsMod.MOD_ID);

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_RADIO_ITEM =
            ITEMS.register("camp_radio", () -> new BlockItem(CAMP_RADIO.get(),
                    new Item.Properties()));

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, SurvivalPointsMod.MOD_ID);

    public static final net.minecraftforge.registries.RegistryObject<BlockEntityType<CampRadioBlockEntity>> CAMP_RADIO_BE =
            BLOCK_ENTITIES.register("camp_radio", () ->
                    BlockEntityType.Builder.of(CampRadioBlockEntity::new, CAMP_RADIO.get()).build(null));

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
    }
}
