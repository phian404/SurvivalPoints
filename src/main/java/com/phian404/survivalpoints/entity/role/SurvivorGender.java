package com.phian404.survivalpoints.entity.role;

import javax.annotation.Nullable;

/**
 * Пол выжившего NPC.
 *
 * <p>Пол — самостоятельное постоянное свойство сущности, а не вычисляемая
 * величина. Он назначается один раз при первичном создании (по скину), затем
 * хранится в NBT и переносится при смене роли. Определять пол по имени текстуры
 * при каждом рендере нельзя: это делает рендер источником истины и ломает
 * игровую логику, которой нужен пол без обращения к клиентским ресурсам.
 *
 * <p>Пол также определяет геометрию модели: {@code MALE} — WIDE (руки 4px),
 * {@code FEMALE} — SLIM (руки 3px).
 */
public enum SurvivorGender {
    MALE,
    FEMALE;

    private static final SurvivorGender[] VALUES = values();

    /**
     * Безопасно разбирает пол по ordinal из NBT/сети.
     *
     * <p>Повреждённое или устаревшее значение не должно ронять загрузку мира,
     * поэтому при выходе за границы возвращается {@link #MALE}.
     */
    public static SurvivorGender byOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= VALUES.length) {
            return MALE;
        }
        return VALUES[ordinal];
    }

    /**
     * Разбирает пол по имени, с тем же безопасным поведением, что и
     * {@link #byOrdinal(int)}.
     */
    public static SurvivorGender byName(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return MALE;
        }
        for (SurvivorGender gender : VALUES) {
            if (gender.name().equals(name)) {
                return gender;
            }
        }
        return MALE;
    }
}
