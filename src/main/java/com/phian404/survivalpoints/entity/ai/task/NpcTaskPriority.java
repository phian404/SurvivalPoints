package com.phian404.survivalpoints.entity.ai.task;

/**
 * Категория деятельности NPC и её приоритет.
 *
 * <p>Единственный источник числовых приоритетов верхнего уровня. Это не
 * приоритеты Minecraft {@code GoalSelector}: они управляют исполнением Goals,
 * а здесь определяется категория текущей деятельности NPC. Чем больше число,
 * тем выше приоритет.
 *
 * <p>Изменение значения группы или добавление новой группы не требует правки
 * исполнительных Goals — они сравнивают приоритеты через {@link #higherThan}.
 */
public enum NpcTaskPriority {

    /** Отдых и повседневная деятельность. */
    REST(0),
    /** Работа и обязанности. */
    WORK(1),
    /** Чрезвычайные ситуации и защита лагеря. */
    DEFENSE(2);

    private final int value;

    NpcTaskPriority(int value) {
        this.value = value;
    }

    public int value() {
        return this.value;
    }

    /** Имеет ли эта категория больший приоритет, чем другая. */
    public boolean higherThan(NpcTaskPriority other) {
        return other != null && this.value > other.value;
    }

    /** Может ли эта категория прервать другую. */
    public boolean canInterrupt(NpcTaskPriority other) {
        return higherThan(other);
    }
}
