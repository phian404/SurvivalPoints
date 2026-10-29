package com.phian404.survivalpoints;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;

public class ModModelLayers {
    // Используем новый конструктор ModelLayerLocation с двумя аргументами
    public static final ModelLayerLocation BASE_GUARDIAN_LAYER = new ModelLayerLocation(
        new ResourceLocation(ModConstants.MOD_ID, "base_guardian"), // Используем конструктор ResourceLocation с двумя аргументами
        "main"
    );

    /**
     * Геометрия выжившего с клиновидным выступом торса.
     *
     * <p>Отдельные слои для WIDE и SLIM: выступ добавляется к той же геометрии,
     * что и ванильный игрок, поэтому разница в руках (4px / 3px) сохраняется.
     */
    public static final ModelLayerLocation SURVIVOR_BODY = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(ModConstants.MOD_ID, "survivor_body"),
        "main"
    );

    public static final ModelLayerLocation SURVIVOR_BODY_SLIM = new ModelLayerLocation(
        ResourceLocation.fromNamespaceAndPath(ModConstants.MOD_ID, "survivor_body_slim"),
        "main"
    );
}