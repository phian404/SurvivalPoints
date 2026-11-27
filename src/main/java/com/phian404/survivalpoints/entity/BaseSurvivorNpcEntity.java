package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.ai.CampWanderGoal;
import com.phian404.survivalpoints.entity.ai.FarmerStandWorkGoal;
import com.phian404.survivalpoints.entity.role.RoleAssignmentService;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

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
     * Поведение мирного жителя: прогулка по лагерю, осмотр и взгляд на игрока.
     *
     * <p>{@link CampWanderGoal} сама проверяет наличие лагеря и границы круга,
     * поэтому жители без привязки сохраняют прежнее поведение. Взгляд на игрока
     * использует только флаг LOOK и не прерывает прогулку.
     */
    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Работа фермера перебивает прогулку; для остальных Goal не активен.
        FarmerStandWorkGoal workGoal = new FarmerStandWorkGoal(this);
        this.workGoal = workGoal;
        this.goalSelector.addGoal(1, workGoal);
        this.goalSelector.addGoal(2, new CampWanderGoal(this));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
    }

    /** Ссылка на рабочую цель для отладки и тестов; {@code null} до registerGoals. */
    @Nullable
    public FarmerStandWorkGoal getWorkGoal() {
        return this.workGoal;
    }

    @Nullable
    private FarmerStandWorkGoal workGoal;

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

    /**
     * Скорость доворота взгляда, градусов за тик. AI-цель тикает через тик, а
     * ванильный {@code LookControl} живёт 2 тика и обнуляет наклон, поэтому
     * голову ведёт сама сущность — каждый тик, но не быстрее этого шага.
     */
    private static final float GAZE_STEP = 10.0F;
    /** Предел наклона головы, градусов (ванильный {@code getMaxHeadXRot()}). */
    private static final float GAZE_PITCH_LIMIT = 40.0F;

    private boolean gazeActive;
    private boolean gazeReleasing;
    private float gazeYaw;
    private float gazePitch;
    private float gazeTargetYaw;
    private float gazeTargetPitch;

    /**
     * Направить взгляд в точку. Голова идёт к ней плавно, пока цель не сменится.
     *
     * <p>В отличие от {@code LookControl}, ведение не привязано к тику AI-цели:
     * иначе между вызовами контрол успевает выровнять голову по телу, и она
     * дёргается. Ведение полностью перекрывает {@code LookControl}, пока
     * активно, поэтому отпускать его нужно через {@link #releaseGaze()}.
     */
    public void aimGaze(double x, double y, double z) {
        Vec3 eye = this.getEyePosition();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        if (!this.gazeActive) {
            this.gazeYaw = this.getYHeadRot();
            this.gazePitch = this.getXRot();
            this.gazeActive = true;
        }
        this.gazeReleasing = false;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        // Точка ровно над/под NPC: азимут не определён, оставляем прежний,
        // иначе голова дёргается в случайную сторону.
        if (horiz > 0.05D) {
            this.gazeTargetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        this.gazeTargetPitch = Mth.clamp(
                (float) Math.toDegrees(Math.atan2(-dy, horiz)),
                -GAZE_PITCH_LIMIT, GAZE_PITCH_LIMIT);
    }

    /**
     * Отпустить взгляд: голова плавно возвращается вперёд, к направлению тела,
     * и только потом управление получает {@code LookControl}. Если отпустить
     * сразу, он обнулит наклон за один тик — это и есть видимый рывок.
     */
    public void releaseGaze() {
        if (!this.gazeActive) {
            return;
        }
        this.gazeReleasing = true;
    }

    /** Взгляд ведёт сущность, а не ванильный {@code LookControl}. */
    public boolean isGazeActive() {
        return this.gazeActive;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // После super: LookControl уже обнулил наклон и подвинул голову к телу,
        // теперь записываем своё положение — оно и уйдёт клиенту в этом тике.
        if (!this.gazeActive) {
            return;
        }
        if (this.gazeReleasing) {
            this.gazeTargetPitch = 0.0F;
            this.gazeTargetYaw = this.yBodyRot;
        }
        this.gazeYaw = Mth.approachDegrees(this.gazeYaw, this.gazeTargetYaw, GAZE_STEP);
        this.gazePitch += Mth.clamp(this.gazeTargetPitch - this.gazePitch, -GAZE_STEP, GAZE_STEP);
        this.setYHeadRot(this.gazeYaw);
        this.setXRot(this.gazePitch);
        if (this.gazeReleasing
                && Math.abs(Mth.wrapDegrees(this.gazeTargetYaw - this.gazeYaw)) < 0.5F
                && Math.abs(this.gazeTargetPitch - this.gazePitch) < 0.5F) {
            this.gazeActive = false;
            this.gazeReleasing = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Mob, в отличие от Monster и Player, сам не тикает замах:
        // без этого swing() не проигрывает анимацию руки.
        this.updateSwingTime();
    }
}
