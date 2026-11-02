package com.phian404.survivalpoints.camp.block;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;

/**
 * Ворота лагеря (временная версия для полигона).
 *
 * <p>Дверь 1×2 на ванильном {@link DoorBlock}: ручное открытие игроком
 * ({@code canOpenByHand=true}) и металлические звуки. Авто-закрытие ночью делает
 * {@code CampManager.tickCamps} через {@code DoorBlock.setOpen(null, ...)}.
 *
 * <p><b>Будущее:</b> выдвижные горизонтальные ворота 3×3 (мультиблок) заменят
 * эту версию — решение владельца, отдельная задача вне волны 2.
 */
public class CampGateBlock extends DoorBlock {

    /** Металлическая дверь, которую можно открыть рукой. */
    public static final BlockSetType CAMP_GATE_TYPE = new BlockSetType(
            "survivalpoints_camp_gate",
            true,
            SoundType.METAL,
            SoundEvents.IRON_DOOR_CLOSE,
            SoundEvents.IRON_DOOR_OPEN,
            SoundEvents.IRON_TRAPDOOR_CLOSE,
            SoundEvents.IRON_TRAPDOOR_OPEN,
            SoundEvents.METAL_PRESSURE_PLATE_CLICK_OFF,
            SoundEvents.METAL_PRESSURE_PLATE_CLICK_ON,
            SoundEvents.STONE_BUTTON_CLICK_OFF,
            SoundEvents.STONE_BUTTON_CLICK_ON
    );

    public CampGateBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(4.0F, 8.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.METAL)
                .noOcclusion(), CAMP_GATE_TYPE);
    }
}
