package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.tags.FluidTags;

/**
 * Детектор аномалий движения стража.
 *
 * <p>Ловит три разных сценария, наблюдаемых в игре:
 *
 * <ol>
 *   <li><b>ЗАВИСАНИЕ</b> — страж в воздухе и НЕ падает дольше
 *       {@link GuardianTuning#HANG_DETECT_TICKS} тиков. Старый симптом
 *       «висит и не приземляется».</li>
 *   <li><b>ПОДСКОК</b> — серия коротких подскоков в углу: airborne → grounded →
 *       airborne несколько раз за короткое окно. Ванильный {@code MoveControl}
 *       прыгает, когда моб «внутри блока с коллизией», то есть при упоре в стену;
 *       при приземлении счётчик «ЗАВИСАНИЯ» обнуляется, поэтому тот детектор
 *       этот случай не видит. Внешне это и есть «зависание на полсекунды».</li>
 *   <li><b>ТОНЕТ</b> — страж в воде и вертикальная скорость направлена вниз
 *       дольше {@link GuardianTuning#SINK_DETECT_TICKS} тиков. Раньше детектор
 *       в воде вообще выключался, поэтому утопление в лог не попадало.</li>
 * </ol>
 *
 * <p>Каждый отчёт срабатывает один раз на эпизод, чтобы не залить лог повторами.
 * Диагностика только пишет в лог — на поведение не влияет.
 *
 * <p>Включён по умолчанию; выключается командой {@code /sp_hang off}.
 */
public final class GuardianHangDetector {

    /** Включён ли детектор. */
    private static boolean enabled = true;

    private GuardianHangDetector() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /**
     * Проверить, нет ли аномалии движения.
     *
     * <p>Вызывается каждый серверный тик из {@code BaseGuardianEntity.tick()}.
     *
     * @param guard страж
     */
    public static void check(BaseGuardianEntity guard) {
        if (!enabled) {
            return;
        }

        boolean grounded = guard.onGround();
        boolean inFluid = guard.isInWater() || guard.isInLava();

        checkSinking(guard, inFluid);
        checkBouncing(guard, grounded);
        checkHanging(guard, grounded, inFluid);
        // Замер M1/M5: зависание по описанию владельца — «встал на невидимый
        // полублок». Это НЕ полёт: onGround() переключается, а высота не
        // меняется. Прежние детекторы такое не видели, поэтому и был всего
        // один отчёт на 3–4 визуальных зависания.
        checkFloatHang(guard, grounded);

        // Замер M8: то, что владелец видит как «зависание», на деле —
        // отбрасывание от удара (подъём почти на блок с ровной дугой и
        // jumping=false). Считаем именно такие фазы, чтобы в логе была одна
        // запись на каждый визуальный случай, а не одна на четыре.
        checkKnockbackHang(guard, grounded);
    }

    /**
     * ОТБРОС: высокий полёт с почти нулевой вертикальной скоростью у вершины.
     *
     * <p>Основание — след движения из прогона 29.10:
     * <pre>
     * -62.00/G → -61.64/a → -61.36/a → -61.17/a → -61.06/a → -61.04/a → -61.09/a …
     * vy:       0.275      0.191      0.109      0.028     -0.050    -0.128
     * j:        -          -          -          -         -         -
     * </pre>
     * Подъём почти на блок, ровная симметричная дуга, прыжок никто не заказывал.
     * Это отбрасывание: ванильный {@code knockback} даёт {@code vy ≈ 0.4}.
     * Прыжок отличается и высотой (0.42), и {@code jumping=true}.
     *
     * <p>Порог {@link GuardianTuning#KNOCKBACK_MIN_RISE} (0.8) стоит между
     * шагом на полублок (0.6) и отбрасыванием (≈1.0) — так обычный подъём по
     * ступеньке в лог не попадает.
     *
     * <p>Отдельно считаем «зависание у вершины»: если {@code |vy|} мала дольше
     * {@link GuardianTuning#KNOCKBACK_HANG_TICKS} тиков, вертикаль кто-то гасит.
     */
    private static void checkKnockbackHang(BaseGuardianEntity guard, boolean grounded) {
        double y = guard.getY();
        double vy = guard.getDeltaMovement().y;

        if (grounded) {
            if (guard.getAirPhaseStartY() != 0.0D || guard.getKnockbackHangTicks() > 0) {
                guard.setAirPhaseStartY(0.0D);
                guard.setAirPhaseMaxY(0.0D);
                guard.setKnockbackHangTicks(0);
            }
            guard.setKnockbackReported(false);
            return;
        }

        // Начало воздушной фазы: запоминаем высоту, с которой оторвались.
        if (guard.getAirPhaseStartY() == 0.0D) {
            guard.setAirPhaseStartY(y);
        }
        if (y > guard.getAirPhaseMaxY()) {
            guard.setAirPhaseMaxY(y);
        }

        double rise = guard.getAirPhaseMaxY() - guard.getAirPhaseStartY();
        if (rise < GuardianTuning.KNOCKBACK_MIN_RISE) {
            // Низкий подъём — это шаг на полублок, не отбрасывание.
            return;
        }

        // Высокий полёт: считаем тики «у вершины».
        if (Math.abs(vy) <= GuardianTuning.KNOCKBACK_HANG_VY) {
            guard.setKnockbackHangTicks(guard.getKnockbackHangTicks() + 1);
        } else {
            guard.setKnockbackHangTicks(0);
        }

        if (guard.getKnockbackHangTicks() < GuardianTuning.KNOCKBACK_HANG_TICKS
                || guard.isKnockbackReported()) {
            return;
        }

        guard.setKnockbackReported(true);
        GuardianTrace.always("ОТБРОС g" + guard.getId()
                + " | подъём=" + GuardianSnapshot.fmt(rise, 2)
                + " | тиков у вершины=" + guard.getKnockbackHangTicks()
                + " | vy=" + GuardianSnapshot.fmt(vy, 3)
                + " | stun=" + guard.getDamageStunTicks()
                + " | opЗаказ=" + guard.getMoveControlOperationBeforeTick()
                + " | " + GuardianSnapshot.oneLine(guard)
                + "\n    след(тик y/vy/g/j/zza/xxa/op/stun/hColl): "
                + guard.describeMotionTrail());
        guard.clearMotionTrail();
    }

    /**
     * «Невидимый полублок» — переключения {@code onGround} без реального полёта.
     *
     * <p>Как выглядит в игре: страж встаёт в воздухе, будто под ним блок, затем
     * «отвисает» и падает. Как выглядит в данных: {@code onGround()} меняет
     * значение, а {@code y} при этом остаётся практически неизменной.
     *
     * <p>Отличие от настоящего прыжка — размах высоты. Ванильный прыжок даёт
     * подъём около 0.42 блока; здесь {@link GuardianTuning#FLOAT_HANG_MIN_DY}
     * и меньше. Поэтому серия таких переключений — аномалия, а прыжок — нет.
     *
     * <p>В отчёт попадает след движения (M1): высота, вертикальная скорость,
     * вход движения, флаг прыжка и операция {@code MoveControl} за последние
     * тики. По нему видно, чем именно страж «держался» в воздухе.
     */
    private static void checkFloatHang(BaseGuardianEntity guard, boolean grounded) {
        boolean prev = guard.isPrevGroundedDiag();
        double y = guard.getY();

        if (grounded == prev) {
            // Переключения нет. Пока страж в воздухе — обновляем размах высоты:
            // он отличает настоящий прыжок от зависания.
            if (!grounded) {
                double span = Math.abs(y - guard.getLastGroundToggleY());
                if (span > guard.getAirborneYSpan()) {
                    guard.setAirborneYSpan(span);
                }
            }
            return;
        }

        // Переключение onGround в этом тике.
        guard.setPrevGroundedDiag(grounded);

        if (!grounded) {
            // Отрыв от земли: запоминаем высоту, от которой считаем размах.
            guard.setLastGroundToggleY(y);
            guard.setAirborneYSpan(0.0D);
            guard.setLastGroundToggleTick(guard.tickCount);
            return;
        }

        // Приземление. Если размах меньше порога — полёта не было, это аномалия.
        double span = guard.getAirborneYSpan();
        int now = guard.tickCount;

        if (span >= GuardianTuning.FLOAT_HANG_MIN_DY) {
            // Настоящий прыжок или падение — серия прервалась.
            guard.setFloatHangCount(0);
            guard.setFloatHangReported(false);
            return;
        }

        int sinceLast = now - guard.getLastGroundToggleTick();
        if (sinceLast <= GuardianTuning.FLOAT_HANG_WINDOW_TICKS && guard.getFloatHangCount() > 0) {
            guard.setFloatHangCount(guard.getFloatHangCount() + 1);
        } else {
            guard.setFloatHangCount(1);
            guard.setFloatHangReported(false);
        }
        guard.setLastGroundToggleTick(now);

        if (guard.getFloatHangCount() < GuardianTuning.FLOAT_HANG_REPORT_COUNT
                || guard.isFloatHangReported()) {
            return;
        }

        guard.setFloatHangReported(true);
        GuardianTrace.always("ПОЛУБЛОК g" + guard.getId()
                + " | миганий=" + guard.getFloatHangCount()
                + " | размахY=" + GuardianSnapshot.fmt(span, 3)
                + " | opЗаказ=" + guard.getMoveControlOperationBeforeTick()
                + " | jump=" + guard.isJumpFlagSet()
                + " | источникПрыжка=" + guard.getJumpSource()
                + " | прыжковВсего=" + guard.getJumpCount()
                + " | блокПод=" + guard.getBlockBelowName()
                + " | надБлоком=" + GuardianSnapshot.fmt(guard.getHeightAboveBlock(), 3)
                + " | strafe=" + guard.isStrafeStepActive()
                + " | hColl=" + guard.horizontalCollision
                + " | stun=" + guard.getDamageStunTicks()
                + " | " + GuardianSnapshot.oneLine(guard)
                + "\n    след(тик y/vy/g/j/zza/xxa/op): " + guard.describeMotionTrail());
        guard.clearMotionTrail();
    }

    /**
     * Замер M2: считает отрывы и ловит «залипший» флаг прыжка.
     *
     * <p>Первый отчёт по D1 показал {@code opОтрыва=WAIT}. В состоянии
     * {@code WAIT} {@code MoveControl} прыжок не заказывает — значит флаг
     * выставил кто-то другой и, возможно, не снял. Настоящий источник теперь
     * пишется из перехвата {@code setJumping} (см. {@code BaseGuardianEntity}),
     * а здесь мы ловим второй вариант: флаг остаётся {@code true} много тиков
     * подряд, и страж «подпрыгивает» без чьего-либо заказа.
     *
     * <p>Порог намеренно щедрый (полсекунды): {@code FloatGoal} держит флаг,
     * пока страж в воде, — это норма. Аномалия — когда флаг живёт дольше и
     * при этом страж не в воде.
     */
    private static void checkJumpingFlag(BaseGuardianEntity guard, boolean grounded) {
        if (!guard.isJumpFlagSet()) {
            guard.setJumpingStreak(0);
            guard.setJumpingStreakReported(false);
            return;
        }

        guard.setJumpingStreak(guard.getJumpingStreak() + 1);

        // В воде длинный флаг — это FloatGoal, он так и должен работать.
        if (guard.isInWater() || guard.isInLava()) {
            return;
        }

        if (guard.getJumpingStreak() < GuardianTuning.HANG_DETECT_TICKS
                || guard.isJumpingStreakReported()) {
            return;
        }

        guard.setJumpingStreakReported(true);
        GuardianTrace.always("ПРЫЖОК ЗАЛИП g" + guard.getId()
                + " | тиков=" + guard.getJumpingStreak()
                + " | источник=" + guard.getJumpSource()
                + " | opЗаказ=" + guard.getMoveControlOperationBeforeTick()
                + " | grounded=" + grounded
                + " | water=" + guard.isInWater()
                + " | " + GuardianSnapshot.oneLine(guard));
    }

    /**
     * ТОНЕТ: в воде и стабильно движется вниз.
     *
     * <p>Ванильное всплытие требует, чтобы моб сам «прыгал» в воде; если вход
     * движения нулевой, страж медленно опускается. Считаем тики непрерывного
     * погружения и пишем снимок — по нему видно, была ли горизонтальная скорость
     * и какая операция была у {@code MoveControl}.
     */
    private static void checkSinking(BaseGuardianEntity guard, boolean inFluid) {
        boolean water = guard.isInWater() && !guard.isInLava();

        if (!water) {
            guard.setSinkingTicks(0);
            guard.setSinkingReported(false);
            return;
        }

        // КРИТЕРИЙ УТОПЛЕНИЯ — ГЛАЗА ПОД ВОДОЙ, а не «вертикальная скорость вниз».
        //
        // Первая версия считала утоплением любое погружение (vy < -0.001), и это
        // дало 31 ложное срабатывание за один прогон: держащийся на поверхности
        // страж всё равно слегка колеблется по вертикали (vy ≈ -0.022), при этом
        // голы работают (goal=RandomStrollGoal / EatBreadGoal) и голова над водой.
        //
        // Настоящее утопление — когда страж уже под водой целиком. Тогда у него
        // нет возможности ни дышать, ни видеть, и это надо ловить.
        boolean underWater = guard.isUnderWater() || guard.isEyeInFluid(FluidTags.WATER);

        if (underWater) {
            guard.setSinkingTicks(guard.getSinkingTicks() + 1);
        } else {
            guard.setSinkingTicks(0);
            guard.setSinkingReported(false);
        }

        if (guard.getSinkingTicks() < GuardianTuning.SINK_DETECT_TICKS || guard.isSinkingReported()) {
            return;
        }

        guard.setSinkingReported(true);
        GuardianTrace.always("ТОНЕТ g" + guard.getId()
                + " | тиков под водой=" + guard.getSinkingTicks()
                + " | opЗаказ=" + guard.getMoveControlOperationBeforeTick()
                + " | vy=" + String.format(java.util.Locale.ROOT, "%.3f", guard.getDeltaMovement().y)
                + " | " + GuardianSnapshot.oneLine(guard));
    }

    /**
     * ПОДСКОК: серия «в воздухе → на земле → снова в воздухе» за короткое окно.
     *
     * <p>Именно это выглядит как «зависание на полсекунды»: ванильный
     * {@code MoveControl} прыгает при упоре в препятствие, страж подскакивает и
     * тут же приземляется. Считаем приземления подряд; если за
     * {@link GuardianTuning#BOUNCE_WINDOW_TICKS} тиков их набралось
     * {@link GuardianTuning#BOUNCE_REPORT_COUNT} — пишем снимок.
     */
    private static void checkBouncing(BaseGuardianEntity guard, boolean grounded) {
        // Считаем ТОЛЬКО моменты приземления после реального полёта.
        // Раньше счётчик обновлялся каждый тик на земле, из-за чего серия
        // никогда не сбрасывалась и детектор не мог отличить ходьбу от тряски.
        boolean wasAirborne = guard.isWasAirborne();

        // Замер M2: считаем отрывы и следим за «залипшим» флагом прыжка.
        // Флаг jumping, оставшийся true, — прямой кандидат в причину отрыва
        // при op=WAIT: MoveControl в этом состоянии прыжок не заказывает.
        checkJumpingFlag(guard, grounded);

        // Отрыв от земли: запоминаем операцию ИМЕННО в этот момент.
        // К приземлению ваниль уже снимает JUMPING (по onGround), поэтому в
        // отчёте операция всегда была WAIT и спровоцировавшее прыжок состояние
        // терялось.
        if (wasAirborne && grounded) {
            // Приземление — состояние отрыва уже сохранено, ниже используем его.
        } else if (!wasAirborne && !grounded) {
            // ВАЖНО: берём операцию ДО ванильного тика. Раньше здесь стояла
            // операция после тика, а MoveControl сбрасывает её в WAIT внутри
            // своего tick() — поэтому в отчётах всегда был opОтрыва=WAIT,
            // и вывод «никто не заказывал движение» был ошибочным.
            guard.setTakeoffOperation(guard.getMoveControlOperationBeforeTick());
            // Замер M2: считаем отрывы и запоминаем тик — по ним видно,
            // как часто страж отрывается и совпадает ли это с «зависаниями».
            guard.setJumpCount(guard.getJumpCount() + 1);
            guard.setLastJumpTick(guard.tickCount);
        }
        guard.setWasAirborne(!grounded);

        if (!grounded || !wasAirborne) {
            return;
        }

        // Это приземление после полёта.
        int now = guard.tickCount;
        int sinceLast = now - guard.getLastLandingTick();
        guard.setLastLandingTick(now);

        if (sinceLast <= GuardianTuning.BOUNCE_WINDOW_TICKS && guard.getBounceCount() > 0) {
            // Серия продолжается: подскоки идут чаще, чем раз в окно.
            guard.setBounceCount(guard.getBounceCount() + 1);
        } else {
            // Серия начинается заново.
            guard.setBounceCount(1);
            guard.setBounceReported(false);
        }

        if (guard.getBounceCount() < GuardianTuning.BOUNCE_REPORT_COUNT || guard.isBounceReported()) {
            return;
        }

        guard.setBounceReported(true);
        GuardianTrace.always("ПОДСКОК g" + guard.getId()
                + " | подскоков=" + guard.getBounceCount()
                + " | opОтрыва=" + guard.getTakeoffOperation()
                + " | opЗаказ=" + guard.getMoveControlOperationBeforeTick()
                + " | opПосле=" + guard.getMoveControlOperationAfterTick()
                + " | strafe=" + guard.isStrafeStepActive()
                + " | hColl=" + guard.horizontalCollision
                + " | stun=" + guard.getDamageStunTicks()
                + " | eat=" + guard.isEating()
                + " | charge=" + guard.getCrossbowChargeTime()
                + " | aggr=" + guard.isAggressive()
                + " | меч=" + guard.getMainHandItem().is(net.minecraft.world.item.Items.IRON_SWORD)
                + " | vy=" + String.format(java.util.Locale.ROOT, "%.3f", guard.getDeltaMovement().y)
                + " | " + GuardianSnapshot.oneLine(guard)
                // Замер M6: след движения. В прогоне от 29.10 отчёт ПОЛУБЛОК
                // не сработал ни разу, поэтому динамика флага прыжка не была
                // видна. Здесь она есть: по колонке j (J/-) видно, сопровождался
                // ли отрыв заказом прыжка или страж просто «шагнул» вверх.
                + "\n    след(тик y/vy/g/j/zza/xxa/op): " + guard.describeMotionTrail());
        guard.clearMotionTrail();
    }

    /**
     * ЗАВИСАНИЕ: в воздухе и НЕ падает дольше порога.
     *
     * <p>В воде не проверяем — там своя ветка (ТОНЕТ), иначе два отчёта
     * накладываются друг на друга.
     */
    private static void checkHanging(BaseGuardianEntity guard, boolean grounded, boolean inFluid) {
        if (grounded || inFluid) {
            if (guard.getAirborneTicks() > 0) {
                guard.setAirborneTicks(0);
                if (guard.isHangReported()) {
                    guard.setHangReported(false);
                    GuardianTrace.event(guard, "приземлился");
                }
            }
            return;
        }

        // В воздухе: считаем только если страж НЕ падает.
        // Падение (отрицательная вертикальная скорость) — это нормальный полёт.
        if (guard.getDeltaMovement().y >= -0.001D) {
            guard.setAirborneTicks(guard.getAirborneTicks() + 1);
        } else {
            guard.setAirborneTicks(0);
            guard.setHangReported(false);
            return;
        }

        if (guard.getAirborneTicks() < GuardianTuning.HANG_DETECT_TICKS || guard.isHangReported()) {
            return;
        }

        guard.setHangReported(true);
        String snapshot = GuardianSnapshot.oneLine(guard);
        GuardianTrace.always("ЗАВИСАНИЕ g" + guard.getId()
                + " | opЗаказ=" + guard.getMoveControlOperationBeforeTick()
                + " | " + snapshot);
        GuardianTrace.event(guard, "ЗАВИСАНИЕ в воздухе | " + snapshot);
    }
}
