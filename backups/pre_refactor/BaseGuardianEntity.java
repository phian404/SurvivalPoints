package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.ai.GuardianRangedAttackGoal;
import com.phian404.survivalpoints.entity.ai.GuardianTargetGoal;
import com.phian404.survivalpoints.entity.ai.GuardianRetreatGoal;
import com.phian404.survivalpoints.entity.ai.GuardianEatBreadGoal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public class BaseGuardianEntity extends PathfinderMob {

    public static EntityType<BaseGuardianEntity> TYPE;

    private static final EntityDataAccessor<Boolean> DATA_CHARGING = SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Byte> DATA_COMBAT_STATE = SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BYTE);

    private int crossbowChargeTime = 0;
    private int helpScreamCooldown = 0;
    private int swingSoundCooldown = 0;
    private boolean eating = false;
    private int breadCooldown = 0;

    public enum CombatState { RANGED, MELEE }

    public BaseGuardianEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_CHARGING, false);
        this.entityData.define(DATA_COMBAT_STATE, (byte) 0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new GuardianRetreatGoal(this));
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0D, false));
        this.goalSelector.addGoal(2, new GuardianRangedAttackGoal(this));
        this.goalSelector.addGoal(3, new GuardianEatBreadGoal(this));
        this.goalSelector.addGoal(4, new RandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 8.0F));

        this.targetSelector.addGoal(1, new GuardianTargetGoal(this));
    }

    public void incrementChargeTime() {
        this.crossbowChargeTime++;
    }

    public void resetChargeTime() {
        this.crossbowChargeTime = 0;
    }

    public void decrementChargeTime() {
        this.crossbowChargeTime = Math.max(0, this.crossbowChargeTime - 1);
    }

    private byte getCombatStateId(CombatState state) {
        return (byte) state.ordinal();
    }

    private CombatState getCombatStateFromId(byte id) {
        return id == 1 ? CombatState.MELEE : CombatState.RANGED;
    }

    public void updateCombatState() {
        if (this.isEating()) return;

        // Если страж в состоянии сильной паники (мало ХП), он не меняет оружие, чтобы не дергаться
        if (this.getHealth() < this.getMaxHealth() * 0.4F) return;

        LivingEntity target = this.getTarget();
        if (target == null) {
            if (this.getCombatState() != CombatState.RANGED) {
                this.entityData.set(DATA_COMBAT_STATE, getCombatStateId(CombatState.RANGED));
                this.updateEquipmentByState();
            }
            return;
        }

        double distance = this.distanceTo(target);
        if (this.getCombatState() == CombatState.RANGED && distance < 3.0D) {
            this.entityData.set(DATA_COMBAT_STATE, getCombatStateId(CombatState.MELEE));
            this.updateEquipmentByState();
        } else if (this.getCombatState() == CombatState.MELEE && distance > 7.0D) {
            this.entityData.set(DATA_COMBAT_STATE, getCombatStateId(CombatState.RANGED));
            this.updateEquipmentByState();
        }
    }

    private void updateEquipmentByState() {
        ItemStack currentItem = this.getItemBySlot(EquipmentSlot.MAINHAND);

        if (this.getCombatState() == CombatState.MELEE) {
            if (currentItem == null || !currentItem.is(Items.IRON_SWORD)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
        } else {
            if (currentItem == null || !currentItem.is(Items.CROSSBOW)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            }
        }
    }

    public void performRangedAttack(LivingEntity target, float pullProgress) {
        ItemStack weapon = this.getMainHandItem();
        if (!weapon.is(Items.CROSSBOW)) return;

        Arrow arrow = new Arrow(this.level(), this);
        Vec3 eyePos = this.getEyePosition();
        
        // ИЗМЕНЕНИЕ: добавляем .add(0.0D, 0.3D, 0.0D), чтобы целиться в верхнюю часть головы / чуть выше
        Vec3 targetPos = target.getEyePosition().add(0.0D, 0.4D, 0.0D);
        
        arrow.setPos(eyePos.x, eyePos.y, eyePos.z);

        Vec3 motion = targetPos.subtract(eyePos).normalize().scale(1.6D);
        arrow.setDeltaMovement(motion);

        this.level().addFreshEntity(arrow);
        this.playSound(net.minecraft.sounds.SoundEvents.CROSSBOW_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));

        this.entityData.set(DATA_CHARGING, false);
        updateCrossbowNBT(false);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        // ПРЕДОХРАНИТЕЛЬ: сбрасываем цель, если она мертва или слишком далеко (32 блока)
        LivingEntity target = this.getTarget();
        if (target != null && (!target.isAlive() || this.distanceToSqr(target) > 1024.0D)) {
            this.setTarget(null);
        }

        this.updateCombatState();

        if (this.helpScreamCooldown > 0) {
            this.helpScreamCooldown--;
        }

        if (this.swingSoundCooldown > 0) {
            this.swingSoundCooldown--;
        }

        if (this.breadCooldown > 0) {
            this.breadCooldown--;
        }

        ItemStack weapon = this.getMainHandItem();
        if (weapon.is(Items.CROSSBOW)) {
            boolean isChargingNow = this.crossbowChargeTime > 0;
            if (this.entityData.get(DATA_CHARGING) != isChargingNow) {
                this.entityData.set(DATA_CHARGING, isChargingNow);
                updateCrossbowNBT(isChargingNow);
            }
        }
    }

    @Override
    public void travel(Vec3 movementInput) {
        super.travel(movementInput);
    }

    public void doJump() {
        this.jumpFromGround();
    }

    public boolean isEating() {
        return this.eating;
    }

    public void setEating(boolean eating) {
        this.eating = eating;
    }

    public int getBreadCooldown() {
        return this.breadCooldown;
    }

    public void setBreadCooldown(int cooldown) {
        this.breadCooldown = cooldown;
    }

    @Override
    public void swing(net.minecraft.world.InteractionHand hand) {
        super.swing(hand);
        if (hand == net.minecraft.world.InteractionHand.MAIN_HAND
                && this.getCombatState() == CombatState.MELEE) {
            playSwordSwingSound();
        }
    }

    private void playSwordSwingSound() {
        if (this.swingSoundCooldown > 0) return;
        this.swingSoundCooldown = 8;
        this.playSound(net.minecraft.sounds.SoundEvents.IRON_GOLEM_ATTACK, 0.6F,
                1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    @Override
    public boolean doHurtTarget(Entity entityIn) {
        boolean flag = super.doHurtTarget(entityIn);
        if (flag && this.getCombatState() == CombatState.MELEE) {
            this.playSound(net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 1.0F);
        }
        return flag;
    }

    private void updateCrossbowNBT(boolean charging) {
        ItemStack weapon = this.getMainHandItem();
        if (weapon.isEmpty()) return;

        CompoundTag tag = weapon.getTag();
        if (tag == null) {
            tag = new CompoundTag();
        }

        if (tag.getBoolean("Charged") != charging) {
            tag.putBoolean("Charged", charging);
            weapon.setTag(tag);
        }
    }

    public boolean isCharging() {
        return this.entityData.get(DATA_CHARGING);
    }

    public int getCrossbowChargeTime() {
        return this.crossbowChargeTime;
    }

    public boolean canScreamForHelp() {
        return this.helpScreamCooldown <= 0;
    }

    public void startScreamCooldown() {
        this.helpScreamCooldown = 200;
    }

    public void callForHelp(LivingEntity attacker) {
        if (this.level().isClientSide) return;

        // ПРОВЕРКА КУЛДАУНА В НАЧАЛЕ: чтобы не спамить каждый тик
        if (!this.canScreamForHelp()) return;

        // Сообщение от того, кто зовет на помощь
        this.level().getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("§c[Страж] §fПомогите мне!"), false);

        var allies = this.level().getEntitiesOfClass(BaseGuardianEntity.class,
            this.getBoundingBox().inflate(32.0D));

        for (BaseGuardianEntity ally : allies) {
            if (ally != this) {
                // Устанавливаем цель, даже если она уже была, чтобы обновить её
                ally.setTarget(attacker);
                // Принудительно даем арбалет, чтобы Goal атаки точно сработал
                ally.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
                // Теперь союзник всегда отвечает на крик о помощи
                this.level().getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("§e[Страж-союзник] §fУже бегу!"), false);
            }
        }

        this.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
        this.startScreamCooldown();
    }

    public CombatState getCombatState() {
        return getCombatStateFromId(this.entityData.get(DATA_COMBAT_STATE));
    }

    public void debugLog(String message) {
        if (!this.level().isClientSide && this.level().getServer() != null) {
            this.level().getServer().getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal("§8[DEBUG-Guardian] §f" + message), false);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.25D)
            .add(Attributes.FOLLOW_RANGE, 16.0D)
            .add(Attributes.ATTACK_DAMAGE, 3.0D);
    }

    @SuppressWarnings("deprecation")
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, net.minecraft.world.DifficultyInstance difficulty, MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData, @Nullable CompoundTag dataTag) {
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData, dataTag);
    }
}
