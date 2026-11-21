package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.camp.blockentity.CampRadioBlockEntity;
import com.phian404.survivalpoints.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Генерация материального лагеря в мире.
 *
 * <p>Под-волна 2.1: ставит радио-вышку (якорь) в центр лагеря.
 *
 * <p>Под-волна 2.2 (периметр) временно отключена: legacy-набор ограждений
 * {@code camp_fence} / {@code camp_fence_post} / {@code camp_barbed_wire}
 * удалён при очистке старых попыток. Восстановление периметра — на актуальных
 * блоках {@code post} / {@code *_section} по спеке v1.1.7-final
 * (см. .roo/plans/Fences/fence-legacy-cleanup-plan.md).
 */
public class CampGenerator {

    private CampGenerator() {
    }

    /**
     * Построить материальную часть лагеря.
     *
     * @param level  серверный уровень
     * @param record лагерь
     */
    public static void generate(ServerLevel level, CampRecord record) {
        BlockPos anchor = record.getAnchor();
        // Очистить место под якорь (на случай, если там что-то стоит).
        level.setBlock(anchor, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(anchor, ModBlocks.CAMP_RADIO.get().defaultBlockState(), 3);
        if (level.getBlockEntity(anchor) instanceof CampRadioBlockEntity radio) {
            radio.setCampId(record.getId());
        }
        generatePerimeter(level, record);
    }

    /**
     * Периметр лагеря.
     *
     * <p><b>Временно отключено.</b> Legacy-набор {@code camp_fence},
     * {@code camp_fence_post}, {@code camp_barbed_wire} удалён вместе с
     * остальным мусором старых попыток (см.
     * .roo/plans/Fences/fence-legacy-cleanup-plan.md).
     *
     * <p>TODO: переписать периметр на актуальные блоки ограждений —
     * цоколь из ванильных каменных оград, затем ярусы {@code post} и
     * {@code plank_section} / {@code mesh_section} / {@code sheet_section},
     * сверху — {@code wire_section} (спека v1.1.7-final), с соединениями
     * через {@code updateShape} вторым проходом.
     */
    private static void generatePerimeter(ServerLevel level, CampRecord record) {
        // TODO: миграция периметра на post / *_section (спека v1.1.7-final).
    }

}
