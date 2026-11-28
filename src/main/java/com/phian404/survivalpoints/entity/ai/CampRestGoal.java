package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSchedule;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Вечерний отдых и ночной сон жителя лагеря.
 *
 * <p>Фаза берётся из {@link CampSchedule}: днём гол неактивен, вечером житель
 * идёт к своему месту отдыха и стоит там, ночью — ложится и спит до рассвета.
 * Просыпается сам: на рассвете фаза меняется и гол уступает прогулке, а от
 * удара житель встаёт сразу.
 *
 * <p>Место отдыха не хранится в лагере, а выводится из личности жителя:
 * детерминированный сдвиг вокруг центра лагеря, у каждого свой. Так соседи не
 * спят в одной клетке, а сохранение и загрузка ничего не теряют.
 */
public class CampRestGoal extends Goal {

    /** Насколько близко к месту отдыха считается, что житель дошёл (1.5 блока). */
    private static final double ARRIVE_SQR = 2.25D;

    /** Перестройка маршрута, если путь сбился (тики). */
    private static final int REPATH_TICKS = 20;

    /** Сколько тиков идти к месту отдыха, прежде чем остановиться на месте. */
    private static final int GATHER_TIMEOUT = 400;

    /** Радиус разброса мест отдыха вокруг центра лагеря (блоки). */
    private static final int SPREAD = 2;

    private final BaseSurvivorNpcEntity npc;

    private CampSchedule phase;
    private BlockPos spot;
    private boolean arrived;
    private int repathTicks;
    private int walkingTicks;

    public CampRestGoal(BaseSurvivorNpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        CampSchedule current = CampSchedule.of(level);
        if (current.isWorkTime()) {
            return false;
        }
        if (this.npc.hurtTime > 0) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }
        BlockPos restSpot = restSpot(level, camp);
        if (restSpot == null) {
            return false;
        }
        this.phase = current;
        this.spot = restSpot;
        return true;
    }

    @Override
    public void start() {
        this.arrived = false;
        this.repathTicks = 0;
        this.walkingTicks = 0;
        walkToSpot();
    }

    @Override
    public boolean canContinueToUse() {
        if (!(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        if (this.npc.hurtTime > 0) {
            return false;
        }
        return CampSchedule.of(level) == this.phase && camp(level) != null;
    }

    @Override
    public void tick() {
        this.walkingTicks++;
        if (this.arrived || this.npc.position().distanceToSqr(
                this.spot.getX() + 0.5D, this.spot.getY(), this.spot.getZ() + 0.5D) <= ARRIVE_SQR) {
            this.arrived = true;
            rest();
            return;
        }
        if (this.walkingTicks > GATHER_TIMEOUT) {
            // Не дошёл: путь перекрыт или место занято. Отдыхаем там, где стоим,
            // но не кружим по лагерю всю ночь.
            this.arrived = true;
            rest();
            return;
        }
        if (--this.repathTicks <= 0) {
            this.repathTicks = REPATH_TICKS;
            walkToSpot();
        }
    }

    @Override
    public void stop() {
        this.npc.getNavigation().stop();
        if (this.npc.getPose() == Pose.SLEEPING) {
            this.npc.setPose(Pose.STANDING);
        }
    }

    /** Место отдыха этого жителя (для отладки и тестов). */
    @Nullable
    public BlockPos getSpot() {
        return this.spot;
    }

    /** Спит ли житель уже сейчас (для отладки и тестов). */
    public boolean isSleeping() {
        return this.arrived && this.phase == CampSchedule.NIGHT;
    }

    private void rest() {
        this.npc.getNavigation().stop();
        if (this.phase == CampSchedule.NIGHT) {
            this.npc.setPose(Pose.SLEEPING);
            this.npc.setDeltaMovement(0.0D, 0.0D, 0.0D);
        } else if (this.npc.getPose() == Pose.SLEEPING) {
            this.npc.setPose(Pose.STANDING);
        }
    }

    private void walkToSpot() {
        this.npc.getNavigation().moveTo(this.spot.getX() + 0.5D, this.spot.getY(),
                this.spot.getZ() + 0.5D, 1.0D);
    }

    @Nullable
    private CampRecord camp(ServerLevel level) {
        if (this.npc.getCampId() == null) {
            return null;
        }
        return CampManager.getCamp(level, this.npc.getCampId());
    }

    /**
     * Своё место отдыха: детерминированный сдвиг вокруг центра лагеря, у
     * каждого жителя свой. Первая подходящая клетка из нескольких попыток.
     */
    @Nullable
    private BlockPos restSpot(ServerLevel level, CampRecord camp) {
        BlockPos center = camp.getCenter();
        long seed = this.npc.getIdentityId().getLeastSignificantBits();
        for (int attempt = 0; attempt < 9; attempt++) {
            long hash = seed + attempt * 0x9E3779B97F4A7C15L;
            int dx = (int) Math.floorMod(hash, SPREAD * 2 + 1L) - SPREAD;
            int dz = (int) Math.floorMod(hash >> 8, SPREAD * 2 + 1L) - SPREAD;
            BlockPos candidate = center.offset(dx, 0, dz);
            BlockPos standable = standableCell(level, candidate, camp);
            if (standable != null) {
                return standable;
            }
        }
        return standableCell(level, center, camp);
    }

    /** Клетка, из которой можно лечь: свободны ноги и голова, под ними опора. */
    @Nullable
    private static BlockPos standableCell(ServerLevel level, BlockPos around, CampRecord camp) {
        for (int dy = 2; dy >= -2; dy--) {
            BlockPos pos = around.offset(0, dy, 0);
            if (!camp.contains(pos)) {
                continue;
            }
            BlockState feet = level.getBlockState(pos);
            BlockState head = level.getBlockState(pos.above());
            if (feet.blocksMotion() || head.blocksMotion()
                    || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
                continue;
            }
            if (level.getBlockState(pos.below()).blocksMotion()) {
                return pos;
            }
        }
        return null;
    }
}
