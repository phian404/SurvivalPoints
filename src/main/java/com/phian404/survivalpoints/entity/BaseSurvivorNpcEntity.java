package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.role.RoleAssignmentService;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import javax.annotation.Nullable;

/**
 * Базовый пассивный выживший.
 *
 * <p>Не имеет боевых целей и оружия. Может быть превращён в стража через
 * {@code RoleAssignmentService} — тогда создаётся {@link BaseGuardianEntity}
 * с теми же identity UUID, скином и (если есть) лагерной привязкой.
 */
public class BaseSurvivorNpcEntity extends BaseSurvivorEntity {

    public BaseSurvivorNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setRoleInternal(SurvivorRole.CIVILIAN);
    }

    /**
     * Назначение роли стража при клике арбалетом.
     *
     * <p>Вся логика выполняется на сервере; клиенту возвращается результат,
     * чтобы анимация руки и звук совпали с реальным действием.
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (!RoleAssignmentService.isGuardianAssignmentItem(player.getMainHandItem())) {
            return InteractionResult.PASS;
        }
        if (this.level().isClientSide) {
            // Клиент не решает исход — сервер пришлёт актуальное состояние.
            return InteractionResult.SUCCESS;
        }
        BaseSurvivorEntity replacement = RoleAssignmentService.reassign(this, SurvivorRole.GUARDIAN);
        if (replacement == null) {
            return InteractionResult.PASS;
        }
        RoleAssignmentService.prepareGuardianEquipment(replacement);
        return InteractionResult.CONSUME;
    }

    @SuppressWarnings("deprecation")
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData,
                                        @Nullable net.minecraft.nbt.CompoundTag dataTag) {
        // Скин выбирается один раз на сервере при первичном появлении и затем
        // сохраняется в NBT, поэтому перезаход его не меняет. Рост назначается
        // следом: rollRandomScale() берёт диапазон из уже установленного пола.
        // Имя назначается последним: набор имён зависит от пола.
        if (!this.level().isClientSide && dataTag == null) {
            this.rollRandomSkin();
            this.rollRandomScale();
            this.ensureName();
        }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData, dataTag);
    }

    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, 20.0D)
                .add(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED, 0.25D);
    }
}
