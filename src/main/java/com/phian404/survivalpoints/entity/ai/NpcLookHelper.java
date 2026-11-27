package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
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

    /**
     * Скорость наклона головы, градусов за тик, для мобов без собственного
     * ведения взгляда. Ванильная берётся из {@code Mob.getMaxHeadXRot()} (40)
     * и при смене цели даёт рывок за один-два тика; 10 читается как кивок.
     */
    private static final float PITCH_SPEED = 10.0F;

    /** Попросить LookControl повернуть голову к центру блока. */
    public static void lookAtBlock(Mob mob, BlockPos target) {
        lookAt(mob, target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
    }

    /** Попросить NPC повернуть голову к точке. */
    public static void lookAt(Mob mob, double x, double y, double z) {
        if (mob instanceof BaseSurvivorNpcEntity npc) {
            npc.aimGaze(x, y, z);
            return;
        }
        mob.getLookControl().setLookAt(x, y, z, mob.getMaxHeadYRot(), PITCH_SPEED);
    }

    /** Отпустить взгляд, когда цель заканчивает работу: голова плавно вернётся вперёд. */
    public static void releaseGaze(Mob mob) {
        if (mob instanceof BaseSurvivorNpcEntity npc) {
            npc.releaseGaze();
        }
    }

    /**
     * Проверить, направлена ли голова на блок в горизонтальной плоскости.
     *
     * @param toleranceDegrees допустимое отклонение в градусах
     */
    public static boolean isFacingBlock(Mob mob, BlockPos target, float toleranceDegrees) {
        double dx = target.getX() + 0.5D - mob.getX();
        double dz = target.getZ() + 0.5D - mob.getZ();
        // Цель под ногами: азимут на неё не определён, смотреть на неё значит
        // смотреть вниз. Такую клетку NPC уже «видит».
        if (dx * dx + dz * dz < 0.25D) {
            return true;
        }
        float targetYaw = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        return Math.abs(Mth.wrapDegrees(targetYaw - mob.getYHeadRot()))
                <= Math.max(0.0F, toleranceDegrees);
    }
}
