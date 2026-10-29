package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;

import java.util.Comparator;

/**
 * Поиск цели.
 *
 * <p>Раньше {@code getEntitiesOfClass} вызывался в {@code canUse()}, а тот вызывается
 * каждый тик — то есть поиск монстров шёл постоянно. Теперь пересканирование
 * происходит не чаще одного раза в {@link GuardianTuning#TARGET_SCAN_INTERVAL} тиков.
 */
public class GuardianTargetGoal extends Goal {

    private final BaseGuardianEntity guardian;
    private int lastScanTick = -1000;

    public GuardianTargetGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
    }

    @Override
    public boolean canUse() {
        LivingEntity current = this.guardian.getTarget();
        if (current != null && current.isAlive()
                && this.guardian.distanceToSqr(current) < GuardianTuning.TARGET_DROP_DISTANCE
                * GuardianTuning.TARGET_DROP_DISTANCE) {
            // Текущая цель ещё подходит — не ищем новую.
            return false;
        }

        if (this.guardian.tickCount - this.lastScanTick < GuardianTuning.TARGET_SCAN_INTERVAL) {
            return false;
        }

        return this.findNearestMonster() != null;
    }

    @Override
    public void tick() {
        this.lastScanTick = this.guardian.tickCount;

        LivingEntity nearest = this.findNearestMonster();
        if (nearest != null) {
            this.guardian.setTarget(nearest);
        }
    }

    /**
     * Ближайший монстр, пригодный в цель.
     *
     * <p>Кроме типа и живости учитывается память о недостижимых целях: монстр,
     * от которого страж уже отказался (не видно, не достать), не берётся снова,
     * пока не истечёт {@link GuardianTuning#UNREACHABLE_TARGET_COOLDOWN_TICKS}.
     * Без этого поиск назначал того же зомби обратно, и сброс цели не давал
     * никакого эффекта — страж зацикливался «сбросил → назначил».
     */
    private LivingEntity findNearestMonster() {
        if (this.guardian.level().isClientSide) {
            return null;
        }

        return this.guardian.level().getEntitiesOfClass(LivingEntity.class,
                        this.guardian.getBoundingBox().inflate(GuardianTuning.TARGET_DETECT_DISTANCE),
                        e -> (e instanceof Monster) && !(e instanceof BaseGuardianEntity) && e.isAlive()
                                && !this.guardian.isTargetUnreachable(e))
                .stream()
                .min(Comparator.comparingDouble(this.guardian::distanceToSqr))
                .orElse(null);
    }
}
