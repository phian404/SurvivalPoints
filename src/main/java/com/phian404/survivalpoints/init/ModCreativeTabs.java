package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.SurvivalPointsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SurvivalPointsMod.MOD_ID);

    public static final net.minecraftforge.registries.RegistryObject<CreativeModeTab> SURVIVALPOINTS_TAB =
            TABS.register("survivalpoints", () -> CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup.survivalpoints"))
                    .icon(() -> new ItemStack(ModBlocks.CAMP_RADIO_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModBlocks.CAMP_RADIO_ITEM.get());
                        output.accept(ModBlocks.POST_ITEM.get());
                        output.accept(ModBlocks.PLANK_SECTION_ITEM.get());
                        output.accept(ModBlocks.WIRE_SECTION_ITEM.get());
                        output.accept(ModBlocks.MESH_SECTION_ITEM.get());
                        output.accept(ModBlocks.SHEET_SECTION_ITEM.get());
                        // Legacy-ограждения (camp_fence, camp_fence_post,
                        // camp_barbed_wire, camp_barbed_wire_bracket, camp_wire_web)
                        // скрыты из вкладки: они остаются зарегистрированными ради
                        // CampGenerator, но развитию не подлежат.
                        // Актуальная система — post / *_section по спеке v1.1.7-final.
                        // См. .roo/plans/fences/LEGACY_FENCES_INVENTORY.md
                        output.accept(ModBlocks.CAMP_GATE_ITEM.get());
                        output.accept(ModEntities.BASE_GUARDIAN_SPAWN_EGG.get());
                        output.accept(ModEntities.BASE_SURVIVOR_SPAWN_EGG.get());
                    })
                    .build());

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
