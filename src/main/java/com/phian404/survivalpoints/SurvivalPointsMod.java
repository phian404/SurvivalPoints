package com.phian404.survivalpoints;

import com.phian404.survivalpoints.init.ModBlocks;
import com.phian404.survivalpoints.init.ModCreativeTabs;
import com.phian404.survivalpoints.init.ModEntities;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod("survivalpoints")
public class SurvivalPointsMod {

    public static final String MOD_ID = "survivalpoints";

    public SurvivalPointsMod() {
        // Регистрация типов сущностей живёт в ModEntities — там же, где их
        // используют рендер и создание атрибутов. Раньше тип сущности был
        // зарегистрирован в двух местах (здесь и в ModEntities) под одним и тем же
        // именем survivalpoints:base_guardian, из-за чего живая регистрация
        // зависела от порядка инициализации классов.
        ModEntities.ENTITY_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModBlocks.register(FMLJavaModLoadingContext.get().getModEventBus());
        ModCreativeTabs.register(FMLJavaModLoadingContext.get().getModEventBus());

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
    }

    private void setup(final FMLCommonSetupEvent event) {
        // Общая настройка
    }
}
