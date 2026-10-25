package com.phian404.survivalpoints.entity.role;

import javax.annotation.Nullable;

/**
 * Роль выжившего NPC.
 *
 * <p>Роль определяет, каким entity type представлен NPC в мире. Смена роли
 * выполняется серверной заменой сущности (см. {@link RoleAssignmentService}),
 * а не изменением поля у уже созданного объекта: в Minecraft класс сущности
 * задаётся её {@code EntityType} и не может быть заменён на месте.
 *
 * <p>Роль хранится в NBT вместе с постоянным identity UUID, чтобы после
 * загрузки мира можно было понять, во что NPC должен быть восстановлен.
 */
public enum SurvivorRole {
    /** Базовый выживший: пассивен, не имеет боевого поведения. */
    CIVILIAN,
    /** Страж: полный боевой AI из {@code BaseGuardianEntity}. */
    GUARDIAN;

    private static final SurvivorRole[] VALUES = values();

    /**
     * Безопасно разбирает роль по ordinal из NBT/сети.
     *
     * <p>Повреждённое или устаревшее значение не должно ронять загрузку мира,
     * поэтому при выходе за границы возвращается {@link #CIVILIAN}.
     */
    public static SurvivorRole byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= VALUES.length) {
            return CIVILIAN;
        }
        return VALUES[ordinal];
    }

    /**
     * Разбирает роль по имени, с тем же безопасным поведением, что и
     * {@link #byOrdinal(int)}.
     */
    public static SurvivorRole byName(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return CIVILIAN;
        }
        for (SurvivorRole role : VALUES) {
            if (role.name().equals(name)) {
                return role;
            }
        }
        return CIVILIAN;
    }
}
