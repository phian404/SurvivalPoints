package com.phian404.survivalpoints.entity.ai;

import net.minecraft.world.entity.LivingEntity;

public interface IRangedCombatant {
    boolean isCharging();
    void performRangedAttack(LivingEntity target, float pullProgress);
    void incrementChargeTime();
    void resetChargeTime();
}
