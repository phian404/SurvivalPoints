package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.Comparator;

public class GuardianTargetGoal extends Goal {

    private final BaseGuardianEntity guardian;

    public GuardianTargetGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
    }

        @Override
        public boolean canUse() {
            LivingEntity currentTarget = this.guardian.getTarget();
            // Если цель уже есть и она ОЧЕНЬ близко (< 5 блоков), запрещаем менять цель
            if (currentTarget != null && currentTarget.isAlive() && this.guardian.distanceToSqr(currentTarget) < 25.0D) {
                return false;
            }
            // Если цель есть, но она просто жива и в радиусе 16 блоков, не ищем новую
            if (currentTarget != null && currentTarget.isAlive() && this.guardian.distanceToSqr(currentTarget) < 256.0D) {
                return false;
            }
            // Ищем монстров в радиусе 16 блоков, исключая своих стражей
                return !this.guardian.level().getEntitiesOfClass(LivingEntity.class,
                    this.guardian.getBoundingBox().inflate(16.0D),
                    e -> (e instanceof Monster) && !(e instanceof BaseGuardianEntity)).isEmpty();
        }

        @Override
        public void tick() {
        LivingEntity currentTarget = this.guardian.getTarget();

        // Если цель уже есть, жива и находится в приемлемом радиусе, не переназначаем её каждый тик
        if (currentTarget != null && currentTarget.isAlive() && this.guardian.distanceToSqr(currentTarget) < 256.0D) {
            return;
        }

        // Ищем ближайшего монстра, который не является стражем
        LivingEntity nearest = this.guardian.level().getEntitiesOfClass(LivingEntity.class,
            this.guardian.getBoundingBox().inflate(16.0D),
            e -> (e instanceof Monster) && !(e instanceof BaseGuardianEntity)).stream()
            .min(Comparator.comparingDouble(e -> this.guardian.distanceToSqr(e)))
            .orElse(null);

        if (nearest != null) {
            this.guardian.setTarget(nearest);
        }
    }
}
