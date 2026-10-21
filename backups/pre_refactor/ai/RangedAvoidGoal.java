package com.phian404.survivalpoints.entity.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.ai.goal.Goal;

public class RangedAvoidGoal extends Goal {
    private final PathfinderMob mob;
    private final Class<? extends LivingEntity> avoidEntityClass;
    private final double outerRadius;
    private final double innerRadius;
    private final double slowSpeed;
    private final double fastSpeed;

    public RangedAvoidGoal(PathfinderMob mob, Class<? extends LivingEntity> avoidEntityClass, double outerRadius, double innerRadius, double slowSpeed, double fastSpeed) {
        this.mob = mob;
        this.avoidEntityClass = avoidEntityClass;
        this.outerRadius = outerRadius;
        this.innerRadius = innerRadius;
        this.slowSpeed = slowSpeed;
        this.fastSpeed = fastSpeed;
    }

    @Override
    public boolean canUse() {
        return !this.mob.level().getEntitiesOfClass(avoidEntityClass, 
            this.mob.getBoundingBox().inflate(outerRadius)).isEmpty();
    }

    @Override
    public void tick() {
        LivingEntity nearest = this.mob.level().getEntitiesOfClass(avoidEntityClass, 
            this.mob.getBoundingBox().inflate(outerRadius)).stream().findFirst().orElse(null);

        if (nearest != null) {
            Vec3 targetPos = nearest.position();
            Vec3 currentPos = this.mob.position();
            Vec3 diff = currentPos.subtract(targetPos).normalize();
            double distance = this.mob.distanceTo(nearest);

            double speed = (distance < innerRadius) ? fastSpeed : slowSpeed;
            this.mob.getNavigation().moveTo(targetPos.x - diff.x * 2, targetPos.y, targetPos.z - diff.z * 2, speed);
        }
    }
}
