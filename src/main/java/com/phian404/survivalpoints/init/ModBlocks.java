package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.camp.block.CampBarbedWireBlock;
import com.phian404.survivalpoints.camp.block.CampBarbedWireBracketBlock;
import com.phian404.survivalpoints.camp.block.CampFenceBlock;
import com.phian404.survivalpoints.camp.block.CampFencePostBlock;
import com.phian404.survivalpoints.camp.block.CampGateBlock;
import com.phian404.survivalpoints.camp.block.CampRadioBlock;
import com.phian404.survivalpoints.camp.block.CampWireWebBlock;
import com.phian404.survivalpoints.camp.block.fence.MeshSectionBlock;
import com.phian404.survivalpoints.camp.block.fence.PostBlock;
import com.phian404.survivalpoints.camp.block.fence.SectionBlock;
import com.phian404.survivalpoints.camp.block.fence.SheetSectionBlock;
import com.phian404.survivalpoints.camp.block.fence.WireSectionBlock;
import com.phian404.survivalpoints.camp.blockentity.CampRadioBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, SurvivalPointsMod.MOD_ID);

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_RADIO =
            BLOCKS.register("camp_radio", () -> new CampRadioBlock());

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_FENCE =
            BLOCKS.register("camp_fence", CampFenceBlock::new);

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_FENCE_POST =
            BLOCKS.register("camp_fence_post", CampFencePostBlock::new);

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_BARBED_WIRE =
            BLOCKS.register("camp_barbed_wire", CampBarbedWireBlock::new);

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_GATE =
            BLOCKS.register("camp_gate", CampGateBlock::new);

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_BARBED_WIRE_BRACKET =
            BLOCKS.register("camp_barbed_wire_bracket", CampBarbedWireBracketBlock::new);

    /** ТЕСТОВЫЙ вариант проволоки — паутина (сравнение с camp_barbed_wire). */
    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_WIRE_WEB =
            BLOCKS.register("camp_wire_web", CampWireWebBlock::new);

    // === Модульные ограждения по спеке v1.1.7-final ===
    // Актуальная система. Legacy camp_* выше оставлены ради CampGenerator
    // и скрыты из креативной вкладки. См. .roo/plans/fences/.

    /** Опора периметра: обтёсанное бревно 8×8 px, стекуется вертикально. */
    public static final net.minecraftforge.registries.RegistryObject<Block> POST =
            BLOCKS.register("post", PostBlock::new);

    /** Секция из досок: две доски с просветом. */
    public static final net.minecraftforge.registries.RegistryObject<Block> PLANK_SECTION =
            BLOCKS.register("plank_section", () -> new SectionBlock(SoundType.WOOD));

    /** Секция колючей проволоки: две нити на высотах 4..6 и 12..14. */
    public static final net.minecraftforge.registries.RegistryObject<Block> WIRE_SECTION =
            BLOCKS.register("wire_section", WireSectionBlock::new);

    /** Секция сетки «рабица»: плоская панель 2 px со сшиванием при стековании. */
    public static final net.minecraftforge.registries.RegistryObject<Block> MESH_SECTION =
            BLOCKS.register("mesh_section", MeshSectionBlock::new);

    /** Секция листа: панель с рейками на торцах и сшиванием при стековании. */
    public static final net.minecraftforge.registries.RegistryObject<Block> SHEET_SECTION =
            BLOCKS.register("sheet_section", SheetSectionBlock::new);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SurvivalPointsMod.MOD_ID);

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_RADIO_ITEM =
            ITEMS.register("camp_radio", () -> new BlockItem(CAMP_RADIO.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_FENCE_ITEM =
            ITEMS.register("camp_fence", () -> new BlockItem(CAMP_FENCE.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_FENCE_POST_ITEM =
            ITEMS.register("camp_fence_post", () -> new BlockItem(CAMP_FENCE_POST.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_BARBED_WIRE_ITEM =
            ITEMS.register("camp_barbed_wire", () -> new BlockItem(CAMP_BARBED_WIRE.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_GATE_ITEM =
            ITEMS.register("camp_gate", () -> new BlockItem(CAMP_GATE.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_WIRE_WEB_ITEM =
            ITEMS.register("camp_wire_web", () -> new BlockItem(CAMP_WIRE_WEB.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_BARBED_WIRE_BRACKET_ITEM =
            ITEMS.register("camp_barbed_wire_bracket", () -> new BlockItem(CAMP_BARBED_WIRE_BRACKET.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> POST_ITEM =
            ITEMS.register("post", () -> new BlockItem(POST.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> PLANK_SECTION_ITEM =
            ITEMS.register("plank_section", () -> new BlockItem(PLANK_SECTION.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> WIRE_SECTION_ITEM =
            ITEMS.register("wire_section", () -> new BlockItem(WIRE_SECTION.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> MESH_SECTION_ITEM =
            ITEMS.register("mesh_section", () -> new BlockItem(MESH_SECTION.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> SHEET_SECTION_ITEM =
            ITEMS.register("sheet_section", () -> new BlockItem(SHEET_SECTION.get(), new Item.Properties()));

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
