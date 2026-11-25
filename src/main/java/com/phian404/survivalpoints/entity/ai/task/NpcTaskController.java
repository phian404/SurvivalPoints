package com.phian404.survivalpoints.entity.ai.task;

import javax.annotation.Nullable;

/**
 * Контроллер текущей деятельности NPC.
 *
 * <p>Хранит не более одной активной задачи верхнего уровня и сравнивает
 * приоритеты через {@link NpcTaskPriority}. Не управляет движением и не
 * подменяет Minecraft {@code GoalSelector}: он только решает, какую категорию
 * деятельности NPC выполняет и когда нужно освободить предыдущую задачу.
 *
 * <p>Владелец резервации — источник задачи ({@code CampFarmManager}). Контроллер
 * не снимает резервации сам, а вызывает переданный {@code releaseCallback}.
 * Runtime-состояние не сохраняется в NBT и не переносится при смене роли.
 */
public final class NpcTaskController {

    @Nullable
    private NpcTask active;
    @Nullable
    private Runnable releaseCallback;

    /** Текущая задача или {@code null}. */
    @Nullable
    public NpcTask current() {
        return this.active;
    }

    /** Категория текущей задачи; {@code REST}, если задачи нет. */
    public NpcTaskPriority currentPriority() {
        return this.active == null ? NpcTaskPriority.REST : this.active.getPriority();
    }

    /**
     * Попытаться начать задачу.
     *
     * <p>Задача с меньшим или равным приоритетом не прерывает активную.
     * При прерывании вызывается освобождение предыдущей задачи. После прерывания
     * старая цель не возобновляется — NPC должен заново запросить работу.
     *
     * @return {@code true}, если задача стала активной
     */
    public boolean tryStart(NpcTask task, @Nullable Runnable release) {
        if (task == null) {
            return false;
        }
        if (this.active != null && !task.getPriority().canInterrupt(this.active.getPriority())) {
            return false;
        }
        releaseCurrent();
        this.active = task;
        this.releaseCallback = release;
        return true;
    }

    /** Завершить текущую задачу и освободить резервацию. */
    public void complete() {
        releaseCurrent();
    }

    /** Отменить текущую задачу без возобновления предыдущей. */
    public void cancel() {
        releaseCurrent();
    }

    /** Очистить состояние без вызова освобождения (для смены роли). */
    public void discardWithoutRelease() {
        this.active = null;
        this.releaseCallback = null;
    }

    private void releaseCurrent() {
        Runnable callback = this.releaseCallback;
        this.active = null;
        this.releaseCallback = null;
        if (callback != null) {
            callback.run();
        }
    }
}
