package com.phian404.survivalpoints.entity.ai;

/**
 * УДАЛЕНО 2026-10-21 в рамках рефакторинга ИИ стража.
 *
 * <p>Интерфейс не был реализован ни одним классом ({@code BaseGuardianEntity}
 * содержал все нужные методы, но не объявлял {@code implements IRangedCombatant}),
 * а единственный его потребитель — {@code RangedAttackGoal} — был не дописан
 * (внутри оставался блок с комментарием «нам нужно знать КОГДА выстрелить»)
 * и никогда не подключался.
 *
 * <p>Стрельба теперь живёт в самой сущности
 * ({@code BaseGuardianEntity#updateRangedCombat}), а перемещение — в
 * {@code GuardianRangedAttackGoal} и {@code GuardianRetreatGoal}.
 *
 * <p>Файл можно удалить.
 */
