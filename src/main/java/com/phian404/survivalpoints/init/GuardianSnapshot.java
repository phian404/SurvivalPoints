package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Снимок состояния стража.
 *
 * <p>Используется в трёх местах: подробный отчёт по команде {@code /sp_guard detail},
 * компактная строка в логе трассировки и отчёт детектора «зависания в воздухе».
 *
 * <p>Числа форматируются через {@link Locale#ROOT} — иначе в русской локали
 * десятичная запятая, и по логу нельзя было бы считать значения.
 */
public final class GuardianSnapshot {

    private GuardianSnapshot() {
    }

    /** Компактная строка для лога: состояние, голы, цель, положение. */
    public static String oneLine(BaseGuardianEntity guard) {
        StringBuilder sb = new StringBuilder();
        sb.append("state=").append(guard.getAIState())
                .append(" goal=").append(goalNames(guard))
                .append(" target=").append(targetName(guard))
                .append(" hp=").append(Math.round(guard.getHealth()))
                .append('/').append(Math.round(guard.getMaxHealth()))
                .append(" pos=").append(fmt(guard.getX(), 1)).append(',')
                .append(fmt(guard.getY(), 2)).append(',')
                .append(fmt(guard.getZ(), 1))
                .append(" onGround=").append(guard.onGround())
                .append(" vy=").append(fmt(guard.getDeltaMovement().y, 3))
                .append(" stun=").append(guard.getDamageStunTicks())
                .append(" nav=").append(guard.getNavigation().isDone() ? "нет" : "есть");
        return sb.toString();
    }

    /** Подробный отчёт для чата. */
    public static String detail(BaseGuardianEntity guard, int index) {
        StringBuilder sb = new StringBuilder();

        sb.append("§e#").append(index + 1)
                .append(" §fid=").append(guard.getId())
                .append(" §7state=").append(guard.getAIState())
                .append(" §7hp=").append(Math.round(guard.getHealth()))
                .append('/').append(Math.round(guard.getMaxHealth()))
                .append(" §7weapon=").append(guard.getMainHandItem().getHoverName().getString())
                .append(" §7eating=").append(guard.isEating())
                .append(" §7stun=").append(guard.getDamageStunTicks());

        LivingEntity target = guard.getTarget();
        if (target == null) {
            sb.append(" §7target=-");
        } else {
            sb.append(" §7target=").append(target.getType().getDescription().getString())
                    .append("§7@").append(fmt(guard.distanceTo(target), 1));
        }

        sb.append("\n   §7goals: §f").append(guard.getActiveGoalNames());
        sb.append("\n   §7flags: §f").append(goalFlags(guard));

        sb.append("\n   §7pos: §fx=").append(fmt(guard.getX(), 2))
                .append(" §fy=").append(fmt(guard.getY(), 2))
                .append(" §fz=").append(fmt(guard.getZ(), 2))
                .append(" §7onGround=").append(guard.onGround())
                .append(" §7hColl=").append(guard.horizontalCollision)
                .append(" §7water=").append(guard.isInWater());
        sb.append("\n   §7vel: §fdx=").append(fmt(guard.getDeltaMovement().x, 3))
                .append(" §fdy=").append(fmt(guard.getDeltaMovement().y, 3))
                .append(" §fdz=").append(fmt(guard.getDeltaMovement().z, 3))
                .append(" §7moveSpd=").append(fmt(guard.getMoveSpeed(), 3))
                .append(" §7moveYaw=").append(fmt(guard.getMoveYaw(), 1));

        sb.append("\n   §7nav: §fdone=").append(guard.getNavigation().isDone())
                .append(" §7want=").append(fmt(guard.getMoveControl().getWantedX(), 1))
                .append('/').append(fmt(guard.getMoveControl().getWantedZ(), 1));

        sb.append("\n   §7rot: §fyaw=").append(fmt(guard.getYRot(), 1))
                .append(" §7headYaw=").append(fmt(guard.yHeadRot, 1))
                .append(" §7bodyYaw=").append(fmt(guard.yBodyRot, 1))
                .append(" §7pitch=").append(fmt(guard.getXRot(), 1))
                .append(" §7wantYaw=").append(fmt(guard.getDesiredYaw(), 1))
                .append(" §7aimErr=").append(fmt(guard.getBodyYawError(target), 1));

        sb.append("\n   §7charge=").append(guard.getCrossbowChargeTime())
                .append(" §7shotDelay=").append(guard.getShotDelay())
                .append(" §7shots=").append(guard.getShotsFired())
                .append(" §7hits=").append(guard.getArrowsHit())
                .append(" §7dmg=").append(fmt(guard.getDamageDealt(), 1));

        Vec3 anchor = guard.getStandoffAnchor();
        sb.append("\n   §7standoff=").append(guard.isStandoffReached());
        if (anchor != null) {
            sb.append(" §7anchorDist=").append(fmt(guard.position().subtract(anchor).length(), 1));
        }
        if (guard.hasRallyPoint() && guard.getRallyPoint() != null) {
            sb.append("\n   §7rally: §f").append(guard.getRallyPoint().toShortString());
        }

        return sb.toString();
    }

    /** Имена работающих целей через запятую. */
    public static String goalNames(BaseGuardianEntity guard) {
        return guard.getActiveGoalNames().isEmpty() ? "-" : guard.getActiveGoalNames();
    }

    /** Флаги работающих целей: {@code Цель[MOVE,LOOK]}. */
    public static String goalFlags(BaseGuardianEntity guard) {
        return guard.goalSelector.getRunningGoals()
                .map(GuardianSnapshot::describeWrapped)
                .collect(Collectors.joining(" "));
    }

    private static String describeWrapped(WrappedGoal wrapped) {
        String flags = wrapped.getFlags().stream()
                .map(Goal.Flag::name)
                .collect(Collectors.joining(","));
        return wrapped.getGoal().getClass().getSimpleName() + "[" + flags + "]";
    }

    /** Имя цели с расстоянием. */
    public static String targetName(BaseGuardianEntity guard) {
        LivingEntity target = guard.getTarget();
        if (target == null) {
            return "-";
        }
        return target.getType().getDescription().getString() + "@" + fmt(guard.distanceTo(target), 1);
    }

    /** Число с точкой, независимо от локали. */
    public static String fmt(double value, int decimals) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
