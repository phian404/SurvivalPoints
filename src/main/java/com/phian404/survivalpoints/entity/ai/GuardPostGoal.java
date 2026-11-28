package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampPost;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSchedule;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianFeatureFlags;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Стоянка стража на своём посту лагеря.
 *
 * <p>В покое страж идёт к посту, который ему назначил лагерь
 * ({@link CampRecord#getPostOf}), встаёт в его точку и осматривается: у стены
 * и ворот — сектор наружу, на башне — медленный полный оборот. Из боя страж
 * выходит на пост сам: бой ведут голы с более высоким приоритетом, а этот гол
 * снова получает управление, когда цели нет.
 *
 * <p>Патрульный пост ({@link CampPost.Type#PATROL}) гол не занимает: по смыслу
 * это обход, его ведёт обычная прогулка по лагерю.
 */
public class GuardPostGoal extends Goal {

    /** Насколько близко к точке поста считается, что страж уже на месте (1.5 блока). */
    private static final double ARRIVE_SQR = 2.25D;

    /** Радиус, из которого стража можно довести прямо в клетку поста (2 блока). */
    private static final double NUDGE_RANGE_SQR = 4.0D;

    /** Сколько тиков доводить в клетку, прежде чем принять соседнюю (3 с). */
    private static final int NUDGE_MAX = 60;

    /**
     * Сколько тиков страж может не приближаться к посту, прежде чем гол уступит.
     *
     * <p>Нужен на случай недостижимого поста (верх башни без прохода, пост
     * за забором): без предела страж бесконечно бился бы в стену, а с ним —
     * уходит на обход и пробует снова позже.
     */
    private static final int STALL_TICKS = 300;

    /** Пауза перед новой попыткой занять недостижимый пост (тики). */
    private static final int RETRY_TICKS = 600;

    /** Насколько близко к недостижимому посту страж всё же несёт службу (8 блоков²). */
    private static final double STALL_FALLBACK_SQR = 64.0D;

    /** Половина сектора осмотра у стены и ворот (градусы). */
    private static final float SCAN_AMPLITUDE = 20.0F;

    /** Период полного прохода сектора туда и обратно (тики). */
    private static final float SCAN_PERIOD = 140.0F;

    /** Скорость оборота на башне (градусы за тик): полный круг примерно за 47 с. */
    private static final float TOWER_SPIN_SPEED = 0.3F;

    private final BaseGuardianEntity guardian;

    private CampPost post;
    private CampSchedule phase;
    private Vec3 postVec = Vec3.ZERO;
    private float outwardYaw;
    private boolean arrived;
    private boolean abandoned;
    private int repathTicks;
    private int nudgeTicks;
    private double bestDistSqr = Double.MAX_VALUE;
    private int lastProgressTick;
    private int retryAtTick;

    public GuardPostGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.POST)) {
            return false;
        }
        if (this.guardian.getTarget() != null) {
            return false;
        }
        if (this.guardian.tickCount < this.retryAtTick) {
            return false;
        }
        if (!(this.guardian.level() instanceof ServerLevel level)) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }
        CampSchedule current = CampSchedule.of(level);
        CampPost preferred = preferredPost(camp, current);
        if (preferred == null) {
            return false;
        }
        this.phase = current;
        this.post = preferred;
        this.postVec = Vec3.atBottomCenterOf(preferred.getPos());
        this.outwardYaw = outwardYaw(camp, preferred);
        return true;
    }

    @Override
    public void start() {
        this.arrived = false;
        this.abandoned = false;
        this.repathTicks = 0;
        this.nudgeTicks = 0;
        this.bestDistSqr = Double.MAX_VALUE;
        this.lastProgressTick = this.guardian.tickCount;
        this.walkToPost();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.abandoned || this.guardian.getTarget() != null) {
            return false;
        }
        if (!(this.guardian.level() instanceof ServerLevel level)) {
            return false;
        }
        // Смена фазы суток меняет и пост: гол уступает, canUse выберет новый.
        return CampSchedule.of(level) == this.phase && this.postStillInCamp(level);
    }

    @Override
    public void tick() {
        if (this.arrived) {
            this.standOnPost();
            return;
        }
        // Прибытие считается по клетке, а не по радиусу: pathfinder доводит до
        // соседней клетки, и страж замирал бы на диагонали от поста. Пока он
        // рядом, но не в клетке, доводим его туда напрямую.
        if (this.guardian.blockPosition().equals(this.post.getPos())) {
            this.arrived = true;
            this.nudgeTicks = 0;
            this.standOnPost();
            return;
        }
        double distSqr = this.guardian.position().distanceToSqr(this.postVec);
        if (this.guardian.onGround() && distSqr <= NUDGE_RANGE_SQR) {
            this.nudgeTicks++;
            if (this.nudgeTicks <= NUDGE_MAX) {
                this.guardian.getNavigation().stop();
                this.guardian.getMoveControl().setWantedPosition(
                        this.post.getPos().getX() + 0.5D, this.post.getPos().getY(),
                        this.post.getPos().getZ() + 0.5D, GuardianTuning.NAV_SPEED);
                return;
            }
            // В клетку довести не удалось (проход занят, подъём): стоим рядом
            // с постом — это лучше, чем бесконечно биться в препятствие.
            if (distSqr <= ARRIVE_SQR) {
                this.arrived = true;
                this.nudgeTicks = 0;
                this.standOnPost();
                return;
            }
        }
        if (--this.repathTicks <= 0) {
            this.repathTicks = GuardianTuning.NAV_RETARGET_INTERVAL;
            this.walkToPost();
        }
        if (distSqr < this.bestDistSqr - 0.01D) {
            this.bestDistSqr = distSqr;
            this.lastProgressTick = this.guardian.tickCount;
        } else if (this.guardian.tickCount - this.lastProgressTick > STALL_TICKS) {
            // Пост недостижим (например, верх вышки без прохода). Если страж уже
            // у подножия — несёт службу оттуда, иначе пробует позже ещё раз.
            if (distSqr <= STALL_FALLBACK_SQR) {
                this.arrived = true;
                this.standOnPost();
            } else {
                this.abandoned = true;
                this.retryAtTick = this.guardian.tickCount + RETRY_TICKS;
            }
        }
    }

    /** На месте ли страж уже сейчас (для отладки и тестов). */
    public boolean isAtPost() {
        return this.arrived;
    }

    /** Пост, который гол занял на текущую фазу суток (для отладки и тестов). */
    @Nullable
    public CampPost getPost() {
        return this.post;
    }

    /** Курс наружу от лагеря, от которого считается осмотр (для отладки и тестов). */
    public float getOutwardYaw() {
        return this.outwardYaw;
    }

    private void walkToPost() {
        this.guardian.followPath(this.postVec.x, this.postVec.y, this.postVec.z,
                GuardianTuning.NAV_SPEED);
    }

    private void standOnPost() {
        this.guardian.standStill();
        this.guardian.turnTowardsYaw(this.scanYaw());
    }

    private float scanYaw() {
        if (this.post.getType() == CampPost.Type.TOWER) {
            return Mth.wrapDegrees(this.outwardYaw + this.guardian.tickCount * TOWER_SPIN_SPEED);
        }
        float phase = (this.guardian.tickCount % (int) SCAN_PERIOD) / SCAN_PERIOD * Mth.TWO_PI;
        return Mth.wrapDegrees(this.outwardYaw + Mth.sin(phase) * SCAN_AMPLITUDE);
    }

    private boolean postStillInCamp(ServerLevel level) {
        if (this.post == null) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }
        for (CampPost present : camp.getPosts()) {
            if (present.getId().equals(this.post.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Пост на текущую фазу суток.
     *
     * <p>Днём — свой назначенный пост у ворот или стены; ночью — вышка
     * (своя, если назначена вышка, иначе одна из вышек лагеря, распределённая
     * между стражами по порядку); вечером постов нет — стража отдыхает.
     */
    @Nullable
    private CampPost preferredPost(CampRecord camp, CampSchedule current) {
        if (current == CampSchedule.EVENING) {
            return null;
        }
        CampPost own = camp.getPostOf(this.guardian.getIdentityId());
        if (own != null && own.getType() == CampPost.Type.PATROL) {
            own = null;
        }
        if (current == CampSchedule.NIGHT) {
            if (own != null && own.getType() == CampPost.Type.TOWER) {
                return own;
            }
            CampPost tower = towerFor(camp);
            return tower != null ? tower : own;
        }
        if (own != null && own.getType() != CampPost.Type.TOWER) {
            return own;
        }
        CampPost ground = firstGroundPost(camp);
        return ground != null ? ground : own;
    }

    /** Вышка для стража: несколько вышек делятся между стражами по порядку. */
    @Nullable
    private CampPost towerFor(CampRecord camp) {
        List<CampPost> towers = new ArrayList<>();
        for (CampPost post : camp.getPosts()) {
            if (post.getType() == CampPost.Type.TOWER) {
                towers.add(post);
            }
        }
        if (towers.isEmpty()) {
            return null;
        }
        towers.sort(Comparator.comparing(post -> post.getId().toString()));
        List<UUID> guardians = new ArrayList<>(camp.getGuardianPosts().keySet());
        guardians.sort(Comparator.comparing(UUID::toString));
        int index = Math.max(0, guardians.indexOf(this.guardian.getIdentityId())) % towers.size();
        return towers.get(index);
    }

    /** Первый по порядку пост у ворот или стены. */
    @Nullable
    private static CampPost firstGroundPost(CampRecord camp) {
        List<CampPost> ground = new ArrayList<>();
        for (CampPost post : camp.getPosts()) {
            if (post.getType() == CampPost.Type.GATE || post.getType() == CampPost.Type.WALL) {
                ground.add(post);
            }
        }
        if (ground.isEmpty()) {
            return null;
        }
        ground.sort(Comparator.comparing(post -> post.getId().toString()));
        return ground.get(0);
    }

    private CampRecord camp(ServerLevel level) {
        if (this.guardian.getCampId() == null) {
            return null;
        }
        return CampManager.getCamp(level, this.guardian.getCampId());
    }

    /**
     * Курс наружу от лагеря: заданный у поста, если он есть, иначе — от центра
     * лагеря через точку поста. Так страж у стены и ворот смотрит в поле, а не
     * внутрь двора.
     */
    private static float outwardYaw(CampRecord camp, CampPost post) {
        if (post.getFacingYaw() != 0.0F) {
            return Mth.wrapDegrees(post.getFacingYaw());
        }
        Vec3 center = Vec3.atCenterOf(camp.getCenter());
        double dx = post.getPos().getX() + 0.5D - center.x;
        double dz = post.getPos().getZ() + 0.5D - center.z;
        if (dx == 0.0D && dz == 0.0D) {
            return 0.0F;
        }
        return BaseGuardianEntity.yawToTarget(dx, dz);
    }
}
