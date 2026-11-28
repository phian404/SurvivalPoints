# Документация SurvivalPoints

> Навигатор по источникам проекта. Открывай только документы, относящиеся к текущей задаче.

## Быстрый выбор

| Задача | Источник |
|---|---|
| Текущее состояние кода и правила работы AI | [AI_CONTEXT.md](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) |
| Порядок волн и приоритеты | [ROADMAP.md](Roadmap/ROADMAP.md) |
| Концепция и принципы проекта | [PROJECT_VISION.md](Architecture/PROJECT_VISION.md) |
| Архитектура NPC и identity | [plan.md](Entities/plan.md) |
| Модель NPC и симуляция | [NPC_CAMP_SIMULATION_DESIGN.md](Entities/NPC_CAMP_SIMULATION_DESIGN.md) |
| Имена NPC | [npc-names-plan.md](Entities/npc-names-plan.md) |
| Базовая система лагерей | [DESIGN_CAMP_SYSTEM.md](Camp/DESIGN_CAMP_SYSTEM.md) |
| Социальные и экономические системы лагеря | [DESIGN_CAMP_SOCIAL.md](Camp/DESIGN_CAMP_SOCIAL.md) |
| Радио-вышка | [RADIO_TOWER_DETAIL.md](Camp/RADIO_TOWER_DETAIL.md) |
| Население и площадка лагеря | [camp-population-plan.md](Camp/camp-population-plan.md), [camp-terrain-leveling-plan.md](Camp/camp-terrain-leveling-plan.md) |
| Бой и ИИ стража | [GUARDIAN_REWORK_PLAN.md](Combat/GUARDIAN_REWORK_PLAN.md), [GUARDIAN_STATE_DECISION.md](Combat/GUARDIAN_STATE_DECISION.md) |
| Дефекты движения стража | [guardian-movement-defects-plan.md](Combat/guardian-movement-defects-plan.md) |
| Ограждения: утверждённые требования | [SurvivalPoints_Fences_v1.1.7-final.md](Fences/SurvivalPoints_Fences_v1.1.7-final.md), [Visual Reference v5](Fences/SurvivalPoints_Fences_Visual_Reference_v5.md) |
| Art direction и производство ассетов | [ART_VISION.md](Art/ART_VISION.md), [Vanilla-Friendly Guidelines.md](Art/Vanilla-Friendly%20Guidelines.md), [ASSET_WORKFLOW.md](Art/ASSET_WORKFLOW.md) |
| Фермерская система и идеи строительства | [BUILDER_CONSTRUCTION_IDEA.md](Camp/BUILDER_CONSTRUCTION_IDEA.md), исходники camp/Farm* и FarmerStandWorkGoal.java |
| Команды и проверки | [TESTING.md](Testing/TESTING.md) |
| Креативная вкладка | [CREATIVE_TAB_PLAN.md](Items/CREATIVE_TAB_PLAN.md) |
| Банк идей (не ТЗ) | [CAMP_IDEAS.md](Camp/CAMP_IDEAS.md) |

## Границы источников истины

- **Код** — фактическое текущее поведение и состав реализации.
- **AI_CONTEXT.md** — краткий снимок кода, технические ограничения и рабочие правила; при сомнении перепроверяй исходники.
- **ROADMAP.md** — порядок и приоритеты крупных направлений, но не автоматическое назначение следующей задачи.
- **Системные спецификации** — утверждённые контракты и технические требования. Не считай каждый пункт реализованным.
- **PROJECT_VISION.md** — концепция, не бэклог и не статус-отчёт.
- **TESTING.md** — доступные проверки и границы их покрытия, не журнал результатов.
- **_archive/** — исторические планы и прежние ручные чек-листы; они не являются действующим ТЗ.
- **CAMP_IDEAS.md** и концептуальные документы — идеи, не разрешение на реализацию.

При противоречии между кодом и документом зафиксируй расхождение. Не меняй утверждённое решение без согласования.
