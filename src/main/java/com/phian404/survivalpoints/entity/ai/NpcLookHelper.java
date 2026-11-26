package com.phian404.survivalpoints.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

/**
 * Общие операции ориентации NPC на блоковую цель.
 *
 * <p>Хелпер не управляет GoalSelector, навигацией или временем ожидания:
 * вызывающий Goal сам решает, когда запрашивать поворот и когда действие
 * разрешено. Проверка направления использует горизонтальный поворот головы.
 */
public final class NpcLookHelper {

    private NpcLookHelper() {
    }

    /** Попросить LookControl повернуть голову к центру блока. */
    public static void lookAtBlock(Mob mob, BlockPos target) {
        mob.getLookControl().setLookAt(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D);
    }

    /**
     * Проверить, направлена ли голова на блок в горизонтальной плоскости.
     *
     * @param toleranceDegrees допустимое отклонение в градусах
     */
    public static boolean isFacingBlock(Mob mob, BlockPos target, float toleranceDegrees) {
        double dx = target.getX() + 0.5D - mob.getX();
        double dz = target.getZ() + 0.5D - mob.getZ();
        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        return Math.abs(Mth.wrapDegrees(targetYaw - mob.getYHeadRot()))
                <= Math.max(0.0F, toleranceDegrees);
    }
}
