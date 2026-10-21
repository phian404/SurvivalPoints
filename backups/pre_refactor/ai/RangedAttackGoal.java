package com.phian404.survivalpoints.entity.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.ai.goal.Goal;

public class RangedAttackGoal extends Goal {
    private final PathfinderMob mob;
    private final IRangedCombatant combatant;
    private LivingEntity target;
    private int seeTime;

    public RangedAttackGoal(PathfinderMob mob, IRangedCombatant combatant) {
        this.mob = mob;
        this.combatant = combatant;
    }

    @Override
    public boolean canUse() {
        this.target = this.mob.getTarget();
        if (this.target == null || !this.target.isAlive()) return false;
        return this.mob.getMainHandItem().is(Items.CROSSBOW);
    }

    @Override
    public void start() {
        this.seeTime = 0;
    }

    @Override
    public void tick() {
        if (this.target == null) return;
        
        this.mob.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        double distance = this.mob.distanceToSqr(this.target);
        boolean canSee = this.mob.getSensing().hasLineOfSight(this.target);

        if (canSee) {
            this.seeTime++;
        } else {
            this.seeTime--;
            this.combatant.resetChargeTime();
        }

        if (this.seeTime >= 10 && distance < 1600.0D) {
            this.combatant.incrementChargeTime();
            
            if (this.combatant.isCharging()) {
                // В BaseGuardianEntity crossbowChargeTime == 20 считается полным зарядом
                // Для примера: если зарядка > 20, стреляем
                // Но так как isCharging() возвращает true даже при 1, 
                // нам нужно либо расширить интерфейс, либо считать тики здесь.
                
                // Лучший вариант: если мы в этом Goal, мы просто ждем, 
                // пока сущность сама решит, что она заряжена.
                // Но нам нужно знать КОГДА выстрелить.
            }
        }
    }
}
