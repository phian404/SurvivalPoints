package com.phian404.survivalpoints;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;

public class ModModelLayers {
    // Используем новый конструктор ModelLayerLocation с двумя аргументами
    public static final ModelLayerLocation BASE_GUARDIAN_LAYER = new ModelLayerLocation(
        new ResourceLocation(ModConstants.MOD_ID, "base_guardian"), // Используем конструктор ResourceLocation с двумя аргументами
        "main"
    );
}