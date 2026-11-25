package com.phian404.survivalpoints.entity.role;

import javax.annotation.Nullable;

/**
 * Профессия выжившего NPC.
 *
 * <p>Профессия описывает, чем NPC занимается в лагере, и не смешивается ни с
 * боевой ролью ({@link SurvivorRole}), ни с лидерством, ни с назначением на
 * пост. Смена профессии не требует замены сущности: значение хранится в NBT
 * вместе с постоянным identity UUID и переносится при смене роли.
 *
 * <p>Первая версия содержит только {@link #NONE} и {@link #FARMER}; остальные
 * профессии добавляются отдельно по мере реализации.
 */
public enum SurvivorProfession {

    /** Профессия не назначена. */
    NONE,
    /** Работает на ферме лагеря. */
    FARMER;

    private static final SurvivorProfession[] VALUES = values();

    /**
     * Безопасно разбирает профессию по имени.
     *
     * <p>Повреждённое или устаревшее значение не должно ронять загрузку мира,
     * поэтому при неизвестном имени возвращается {@link #NONE}.
     */
    public static SurvivorProfession byName(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return NONE;
        }
        for (SurvivorProfession profession : VALUES) {
            if (profession.name().equals(name)) {
                return profession;
            }
        }
        return NONE;
    }
}
