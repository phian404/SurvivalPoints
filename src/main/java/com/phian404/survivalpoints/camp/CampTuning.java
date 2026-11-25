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

    /** Компактный аванпост 21×21 (периметр 0..20 по локальным координатам). */
    public static final int CAMP_RADIUS_COMPACT = 10;
    /** Малый лагерь (аванпост). */
    public static final int CAMP_RADIUS_SMALL = 16;
    /** Средний лагерь (крепость). */
    public static final int CAMP_RADIUS_MEDIUM = 24;
    /** Большой лагерь (посёлок). */
    public static final int CAMP_RADIUS_LARGE = 32;
    public static final int CAMP_DEFAULT_RADIUS = CAMP_RADIUS_COMPACT;

    // ==================== Посты и вышки ====================

    public static final int CAMP_POSTS_SMALL = 4;
    public static final int CAMP_POSTS_MEDIUM = 6;
    public static final int CAMP_POSTS_LARGE = 8;

    public static final int CAMP_TOWERS_SMALL = 1;
    public static final int CAMP_TOWERS_MEDIUM = 2;
    public static final int CAMP_TOWERS_LARGE = 4;

    /** Отступ постов от края периметра (чтобы страж не стоял вплотную к забору). */
    public static final int CAMP_POST_INSET = 2;

    /** Высота периметра в ярусах секций над полом двора. */
    public static final int CAMP_WALL_HEIGHT = 3;

    // ==================== Участок ====================

    /**
     * Допустимый перепад высот по площадке лагеря (в блоках).
     *
     * <p>2 блока: площадка выравнивается по медиане рельефа, поэтому низкие
     * клетки подсыпаются, а возвышенности срезаются. Перепад в 2 блока —
     * это максимум, который ещё читается как «ровный двор» после подсыпки
     * края; при большем перепаде насыпь по краю стала бы обрывом.
     */
    public static final int CAMP_MAX_HEIGHT_DELTA = 2;

    /**
     * Ширина наружной полосы вокруг периметра (в блоках).
     *
     * <p>В этой полосе проверяется отсутствие {@code BlockEntity} и
     * выполняется подсыпка края и подхода к воротам. Срез грунта за пределы
     * площадки не выходит: снаружи рельеф только поднимается, чтобы не
     * оставлять обрыв высотой 1–2 блока.
     */
    public static final int CAMP_CLEAR_MARGIN = 3;

    /**
     * Верхняя граница расчистки над полом двора (в блоках).
     *
     * <p>Дерево внутри площадки удаляется целиком: ствол и листва выше пола
     * двора попадают в этот запас. Значение покрывает обычное дерево, но не
     * превращает расчистку в снос произвольных высоких построек.
     */
    public static final int CAMP_CLEAR_HEADROOM = 8;

    /** Минимальный зазор между краями двух лагерей. */
    public static final int CAMP_MIN_GAP_BETWEEN = 8;

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
    public static final int CAMP_MAX_PER_WORLD = 16;
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
    /** Как часто диспетчер фермы обновляет пул задач (в тиках). */
    public static final int FARM_SCAN_INTERVAL = 200;
    /** Максимальная длина названия лагеря. */
    public static final int CAMP_NAME_MAX_LENGTH = 24;
    /** Имя файла данных в мире. */
    public static final String CAMP_DATA_ID = "survivalpoints_camps";

    // ==================== Автоматический поиск площадок (этап 7) ====================

    /**
     * Сторона ячейки кандидата (в блоках).
     *
     * <p>Кандидат стоит строго в центре ячейки. Минимальное расстояние между
     * центрами соседних лагерей: {@code 2*radius + CAMP_MIN_GAP_BETWEEN = 28}.
     * Смещение итогового центра до {@code SITE_SEARCH_RADIUS = 6} по каждой
     * оси даёт {@code 28 + 2*6 = 40}. Увеличение до 56 даёт запас: минимальная
     * разность центров соседних лагерей {@code 56 - 2*6 = 44}, то есть зазор
     * между периметрами {@code 44 - 2*10 = 24} блока.
     */
    public static final int SITE_CELL_SIZE = 56;
    /** Тиков между проходами сканирования ячеек. */
    public static final int SITE_SCAN_INTERVAL = 200;
    /** Сколько лагерей создаётся за один проход. */
    public static final int SITE_MAX_CREATED_PER_SCAN = 1;
    /** Сколько ячеек рассматривается за один проход (включая пропущенные). */
    public static final int SITE_MAX_CELLS_PER_SCAN = 8;
    /** Радиус поиска ячеек вокруг игроков (в блоках). */
    public static final int SITE_PLAYER_SCAN_RADIUS = 256;
    /** Минимальная дистанция от центра лагеря до игрока (в блоках). */
    public static final int SITE_MIN_DISTANCE_TO_PLAYER = 64;
    /** Пауза перед повторной проверкой после трёх временных недоступностей (тиков). */
    public static final int SITE_NOT_READY_RETRY_DELAY = 1200;
    /** Сколько временных недоступностей допускается до паузы. */
    public static final int SITE_NOT_READY_MAX_ATTEMPTS = 3;
    /** Имя файла журнала ячеек в мире. */
    public static final String SITE_JOURNAL_DATA_ID = "survivalpoints_camp_sites";
    /** Тег разрешённых биомов для автоматических лагерей. */
    public static final String SITE_BIOME_ALLOW_TAG = "survivalpoints:camp_site_allowed";

    // ==================== Фронтир впервые исследуемой территории (этап 8.1) ====================

    /** Задержка после входа игрока перед consideration фронтира (тиков = 10 сек). */
    public static final int SITE_JOIN_GRACE_TICKS = 200;
    /** Минимальное горизонтальное перемещение игрока от позиции входа (блоков). */
    public static final int SITE_MIN_PLAYER_TRAVEL = 32;
    /** Предпочтительный минимум дистанции от границы площадки до игрока (блоков). */
    public static final int SITE_PREFERRED_DISTANCE_MIN = 96;
    /** Предпочтительный максимум дистанции от границы площадки до игрока (блоков). */
    public static final int SITE_PREFERRED_DISTANCE_MAX = 140;
    /** Время жизни кандидата в очереди фронтира (тиков = 30 сек). */
    public static final int SITE_FRONTIER_TTL = 600;
    /** Максимальный размер очереди фронтира. */
    public static final int SITE_FRONTIER_QUEUE_LIMIT = 128;
    /** Порог inhabitedTime: чанк с таким или большим временем считается обжитым. */
    public static final long SITE_MAX_INHABITED_TIME = 1000L;

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
