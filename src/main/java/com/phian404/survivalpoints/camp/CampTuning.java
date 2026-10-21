package com.phian404.survivalpoints.camp;

/**
 * Константы лагерной системы.
 *
 * <p>Отдельный класс от {@code GuardianTuning}: там пороги поведения стража
 * (стрельба, движение, рубеж), здесь — параметры лагеря (размер, штат, тревога).
 * Смешивать их в один файл не стоит: иначе любая правка по лагерю задевает
 * файл стража и наоборот.
 */
public final class CampTuning {

    private CampTuning() {
    }

    // ==================== Размеры лагеря ====================

    /** Малый лагерь (аванпост). */
    public static final int CAMP_RADIUS_SMALL = 16;
    /** Средний лагерь (крепость) — размер по умолчанию. */
    public static final int CAMP_RADIUS_MEDIUM = 24;
    /** Большой лагерь (посёлок). */
    public static final int CAMP_RADIUS_LARGE = 32;
    public static final int CAMP_DEFAULT_RADIUS = CAMP_RADIUS_MEDIUM;

    // ==================== Посты и вышки ====================

    public static final int CAMP_POSTS_SMALL = 4;
    public static final int CAMP_POSTS_MEDIUM = 6;
    public static final int CAMP_POSTS_LARGE = 8;

    public static final int CAMP_TOWERS_SMALL = 1;
    public static final int CAMP_TOWERS_MEDIUM = 2;
    public static final int CAMP_TOWERS_LARGE = 4;

    /** Отступ постов от края периметра (чтобы страж не стоял вплотную к забору). */
    public static final int CAMP_POST_INSET = 2;

    // ==================== Штат ====================

    /** Минимум стражей в лагере. */
    public static final int CAMP_MIN_GUARDIANS = 8;
    /** Максимум стражей в лагере. */
    public static final int CAMP_MAX_GUARDIANS = 16;
    /** Как часто лагерь проверяет свой штат (6000 тиков = 5 минут). */
    public static final int CAMP_STAFF_CHECK_INTERVAL = 6000;
    /** Радиус поиска свободного стража для вербовки. */
    public static final int CAMP_RECRUIT_RADIUS = 48;

    // ==================== Потолки ====================

    /** Сколько лагерей может быть в одном мире. */
    public static final int CAMP_MAX_PER_WORLD = 4;
    /** Сколько личных лагерей может быть у одного игрока. */
    public static final int CAMP_MAX_PER_PLAYER = 1;

    // ==================== Тревога ====================

    public static final int CAMP_ALERT_RADIUS_DAY = 40;
    public static final int CAMP_ALERT_RADIUS_NIGHT = 64;

    // ==================== Радио ====================

    /** Радиус объявления по радио (как далеко слышно). */
    public static final int CAMP_RADIO_RANGE = 128;
    /** Как часто радио объявляет о лагере (24000 тиков = игровой день). */
    public static final int CAMP_RADIO_CALL_INTERVAL = 24000;
    /** Радиус, в котором вышка считается ретранслятором чужого лагеря. */
    public static final int CAMP_RETRANSMITTER_RADIUS = 48;

    // ==================== Прочее ====================

    /** Как часто тикают лагеря (раз в секунду). */
    public static final int CAMP_TICK_INTERVAL = 20;
    /** Максимальная длина названия лагеря. */
    public static final int CAMP_NAME_MAX_LENGTH = 24;
    /** Имя файла данных в мире. */
    public static final String CAMP_DATA_ID = "survivalpoints_camps";

    /**
     * Сколько постов у лагеря такого радиуса.
     *
     * @param radius радиус лагеря
     * @return число постов
     */
    public static int postsForRadius(int radius) {
        if (radius <= CAMP_RADIUS_SMALL) {
            return CAMP_POSTS_SMALL;
        }
        if (radius >= CAMP_RADIUS_LARGE) {
            return CAMP_POSTS_LARGE;
        }
        return CAMP_POSTS_MEDIUM;
    }

    /**
     * Сколько вышек у лагеря такого радиуса.
     *
     * @param radius радиус лагеря
     * @return число вышек
     */
    public static int towersForRadius(int radius) {
        if (radius <= CAMP_RADIUS_SMALL) {
            return CAMP_TOWERS_SMALL;
        }
        if (radius >= CAMP_RADIUS_LARGE) {
            return CAMP_TOWERS_LARGE;
        }
        return CAMP_TOWERS_MEDIUM;
    }
}
