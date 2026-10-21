package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

public class GuardianRetreatGoal extends Goal {
    private final BaseGuardianEntity guardian;
    private LivingEntity target;
    private int helpScreamTimer = 0;

    public GuardianRetreatGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
    }

    @Override
    public boolean canUse() {
        this.target = this.guardian.getTarget();
        // Отступление срабатывает при очень низком ХП (30%)
        return this.target != null && this.guardian.getHealth() < this.guardian.getMaxHealth() * 0.3F;
    }

@Override
    public void start() {
        this.helpScreamTimer = 0;
    if (this.target != null) {
            this.guardian.callForHelp(this.target);
        }
    }

    @Override
    public void tick() {
        Vec3 targetPos = this.target.position();
        Vec3 guardianPos = this.guardian.position();

        if (this.target != null) {
            // Таймер зова о помощи (каждые 100 тиков = 5 секунд)
            if (this.helpScreamTimer > 0) {
                this.helpScreamTimer--;
            } else {
                this.guardian.callForHelp(this.target);
                this.helpScreamTimer = 100;
            }

        // Вычисляем вектор от врага к стражу (направление бегства)
        Vec3 moveVec = guardianPos.subtract(targetPos).normalize();
        
        // Скорость отступления
        double speed = 0.35D;
        this.guardian.setDeltaMovement(moveVec.x * speed, this.guardian.getDeltaMovement().y, moveVec.z * speed);
        
            // СМОТРИМ ВПЕРЕД: используем вектор движения, а не позицию цели
        float yaw = (float) Math.toDegrees(Math.atan2(moveVec.x, moveVec.z));
        this.guardian.setYRot(yaw);
    }
}
}
