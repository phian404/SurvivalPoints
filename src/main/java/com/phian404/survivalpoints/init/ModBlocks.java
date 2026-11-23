package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.camp.block.CampGateBlock;
import com.phian404.survivalpoints.camp.block.CampRadioBlock;
import com.phian404.survivalpoints.camp.block.fence.MeshSectionBlock;
import com.phian404.survivalpoints.camp.block.fence.PlankSectionBlock;
import com.phian404.survivalpoints.camp.block.fence.PostBlock;
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

    public static final net.minecraftforge.registries.RegistryObject<Block> CAMP_GATE =
            BLOCKS.register("camp_gate", CampGateBlock::new);

    // === Модульные ограждения по спеке v1.1.7-final ===
    // Единственная система ограждений. Legacy camp_fence / camp_fence_post /
    // camp_barbed_wire / camp_barbed_wire_bracket / camp_wire_web удалены при
    // очистке старых попыток. См. .roo/plans/Fences/fence-legacy-cleanup-plan.md.

    /** Опора периметра: обтёсанное бревно 8×8 px, стекуется вертикально. */
    public static final net.minecraftforge.registries.RegistryObject<Block> POST =
            BLOCKS.register("post", PostBlock::new);

    /** Секция из досок: две доски с просветом, коллизия — забор 1.5 блока. */
    public static final net.minecraftforge.registries.RegistryObject<Block> PLANK_SECTION =
            BLOCKS.register("plank_section", PlankSectionBlock::new);

    /** Секция колючей проволоки: две нити на высотах 4..6 и 12..14. */
    public static final net.minecraftforge.registries.RegistryObject<Block> WIRE_SECTION =
            BLOCKS.register("wire_section", WireSectionBlock::new);

    /** Секция сетки «рабица»: плоская панель 2 px со сшиванием при стековании. */
    public static final net.minecraftforge.registries.RegistryObject<Block> MESH_SECTION =
            BLOCKS.register("mesh_section", MeshSectionBlock::new);

    /** Секция листа: панель с рейками на торцах и сшиванием при стековании. */
    public static final net.minecraftforge.registries.RegistryObject<Block> SHEET_SECTION =
            BLOCKS.register("sheet_section", SheetSectionBlock::new);

    // === Ржавые вариации материалов (решение заказчика) ===
    // Отдельные block-id с той же геометрией и той же логикой; отличаются только
    // текстурой. Сшиваются с базовым вариантом своего материала: см.
    // StackedSectionBlock.isSameMaterial() и isinstance-проверки в подклассах.

    /** Сетка, слабый налёт ржавчины (индекс 0). */
    public static final net.minecraftforge.registries.RegistryObject<Block> RUSTED_MESH_0 =
            BLOCKS.register("rusted_mesh_0", MeshSectionBlock::new);

    /** Сетка, сильный налёт ржавчины (индекс 1). */
    public static final net.minecraftforge.registries.RegistryObject<Block> RUSTED_MESH_1 =
            BLOCKS.register("rusted_mesh_1", MeshSectionBlock::new);

    /** Лист, слабый налёт ржавчины (индекс 0). */
    public static final net.minecraftforge.registries.RegistryObject<Block> RUSTED_SHEET_0 =
            BLOCKS.register("rusted_sheet_0", SheetSectionBlock::new);

    /** Лист, сильный налёт ржавчины (индекс 1). */
    public static final net.minecraftforge.registries.RegistryObject<Block> RUSTED_SHEET_1 =
            BLOCKS.register("rusted_sheet_1", SheetSectionBlock::new);

    /** Проволока, слабый налёт ржавчины (индекс 0). */
    public static final net.minecraftforge.registries.RegistryObject<Block> RUSTED_WIRE_0 =
            BLOCKS.register("rusted_wire_0", WireSectionBlock::new);

    /** Проволока, сильный налёт ржавчины (индекс 1). */
    public static final net.minecraftforge.registries.RegistryObject<Block> RUSTED_WIRE_1 =
            BLOCKS.register("rusted_wire_1", WireSectionBlock::new);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SurvivalPointsMod.MOD_ID);

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_RADIO_ITEM =
            ITEMS.register("camp_radio", () -> new BlockItem(CAMP_RADIO.get(),
                    new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> CAMP_GATE_ITEM =
            ITEMS.register("camp_gate", () -> new BlockItem(CAMP_GATE.get(),
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

    public static final net.minecraftforge.registries.RegistryObject<Item> RUSTED_MESH_0_ITEM =
            ITEMS.register("rusted_mesh_0", () -> new BlockItem(RUSTED_MESH_0.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> RUSTED_MESH_1_ITEM =
            ITEMS.register("rusted_mesh_1", () -> new BlockItem(RUSTED_MESH_1.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> RUSTED_SHEET_0_ITEM =
            ITEMS.register("rusted_sheet_0", () -> new BlockItem(RUSTED_SHEET_0.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> RUSTED_SHEET_1_ITEM =
            ITEMS.register("rusted_sheet_1", () -> new BlockItem(RUSTED_SHEET_1.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> RUSTED_WIRE_0_ITEM =
            ITEMS.register("rusted_wire_0", () -> new BlockItem(RUSTED_WIRE_0.get(), new Item.Properties()));

    public static final net.minecraftforge.registries.RegistryObject<Item> RUSTED_WIRE_1_ITEM =
            ITEMS.register("rusted_wire_1", () -> new BlockItem(RUSTED_WIRE_1.get(), new Item.Properties()));

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
