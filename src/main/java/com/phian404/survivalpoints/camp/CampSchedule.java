package com.phian404.survivalpoints.camp;

import net.minecraft.world.level.Level;

/**
 * Суточный распорядок лагеря.
 *
 * <p>Фаза считается по времени суток ({@code level.getDayTime() % 24000}):
 * 0 — рассвет (06:00), 12000 — закат (18:00), 13000 — ночь (19:00).
 * Всё поведение NPC берёт фазу отсюда, поэтому перемотка времени меняет
 * распорядок сама по себе, без команд и сохранённых состояний.
 *
 * <p>Работа идёт только днём; вечером работа прекращается, а стража сходит с
 * постов и отдыхает; ночью жители спят, стражи занимают вышки.
 */
public enum CampSchedule {

    /** 06:00–18:00: работа, посты заняты. */
    DAY,
    /** 18:00–19:00: работа окончена, посты не занимаются. */
    EVENING,
    /** 19:00–06:00: жители спят, стражи на вышках. */
    NIGHT;

    /** Начало вечера: 18:00 (12000 тиков). */
    public static final long EVENING_START = 12000L;

    /** Начало ночи: 19:00 (13000 тиков). */
    public static final long NIGHT_START = 13000L;

    /** Полные сутки. */
    public static final long DAY_LENGTH = 24000L;

    /** Фаза по времени суток. */
    public static CampSchedule at(long dayTime) {
        long timeOfDay = Math.floorMod(dayTime, DAY_LENGTH);
        if (timeOfDay < EVENING_START) {
            return DAY;
        }
        if (timeOfDay < NIGHT_START) {
            return EVENING;
        }
        return NIGHT;
    }

    /** Фаза мира. */
    public static CampSchedule of(Level level) {
        return at(level.getDayTime());
    }

    /** Идёт ли работа днём. */
    public boolean isWorkTime() {
        return this == DAY;
    }
}
