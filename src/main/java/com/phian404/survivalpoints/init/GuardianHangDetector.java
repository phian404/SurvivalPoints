package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;

/**
 * Детектор «зависания в воздухе».
 *
 * <p>Страж иногда остаётся висеть над землёй после отбрасывания. Причина
 * раньше не была найдена, потому что симптом редкий и не воспроизводится
 * по запросу. Этот детектор ловит сам момент: если страж находится в воздухе
 * и при этом НЕ падает (вертикальная скорость не отрицательная) дольше
 * {@link com.phian404.survivalpoints.init.GuardianTuning#HANG_DETECT_TICKS}
 * тиков — пишется предупреждение в лог с полным снимком состояния.
 *
 * <p>Отчёт срабатывает один раз на «эпизод зависания» (до приземления), чтобы
 * не залить лог повторами.
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
     * Проверить, не висит ли страж в воздухе.
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
        GuardianTrace.always("ЗАВИСАНИЕ g" + guard.getId() + " | " + snapshot);
        GuardianTrace.event(guard, "ЗАВИСАНИЕ в воздухе | " + snapshot);
    }
}
