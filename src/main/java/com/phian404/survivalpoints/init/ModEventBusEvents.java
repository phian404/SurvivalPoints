package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = com.phian404.survivalpoints.SurvivalPointsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEventBusEvents {

    @SubscribeEvent
    public static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.BASE_GUARDIAN.get(), BaseGuardianEntity.createAttributes().build());
        event.put(ModEntities.BASE_SURVIVOR.get(), BaseSurvivorNpcEntity.createAttributes().build());
    }
}