package com.phian404.survivalpoints.camp;

/**
 * Отношение между лагерями.
 *
 * <p>Сейчас это только задел: карта отношений уже хранится в данных лагеря,
 * но перенос репутации между лагерями запланирован на волну 9 — тогда
 * {@code FRIENDLY} начнёт влиять на торговлю, а {@code HOSTILE} — на враждебность.
 */
public enum CampRelation {

    /** Лагеря знают друг друга, но не союзники. */
    NEUTRAL,
    /** Дружественные: обмен ресурсами, общие задания. */
    FRIENDLY,
    /** Враждебные: торговля закрыта, нападения возможны. */
    HOSTILE;

    /**
     * Разобрать название из сохранённых данных.
     *
     * @param name имя отношения
     * @return отношение или {@link #NEUTRAL}, если имя неизвестно
     */
    public static CampRelation byName(String name) {
        for (CampRelation relation : values()) {
            if (relation.name().equalsIgnoreCase(name)) {
                return relation;
            }
        }
        return NEUTRAL;
    }
}
