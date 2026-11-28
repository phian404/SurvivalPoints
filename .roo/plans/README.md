# Документация SurvivalPoints — навигатор

> Короткий индекс. Открывай документы по типу задачи, а не весь каталог подряд.
> Статус реализации проверяй по коду и ROADMAP; дизайн-документ сам по себе не доказывает, что функция уже реализована.

## Как выбрать документ

| Задача | Основной документ |
|---|---|
| Контекст кода, технические ограничения, правила работы | [AI_CONTEXT.md](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) |
| Общая концепция и принципы мода | [PROJECT_VISION.md](Architecture/PROJECT_VISION.md) |
| Порядок волн и общий статус разработки | [ROADMAP.md](Roadmap/ROADMAP.md) |
| Архитектура NPC, identity и роли | [plan.md](Entities/plan.md) |
| Модель NPC, навыки и симуляция | [NPC_CAMP_SIMULATION_DESIGN.md](Entities/NPC_CAMP_SIMULATION_DESIGN.md) |
| Имена NPC | [npc-names-plan.md](Entities/npc-names-plan.md) |
| Базовая система лагерей | [DESIGN_CAMP_SYSTEM.md](Camp/DESIGN_CAMP_SYSTEM.md) |
| Социальные и экономические механики лагеря | [DESIGN_CAMP_SOCIAL.md](Camp/DESIGN_CAMP_SOCIAL.md) |
| Радио-вышка и якорь | [RADIO_TOWER_DETAIL.md](Camp/RADIO_TOWER_DETAIL.md) |
| Население лагеря | [camp-population-plan.md](Camp/camp-population-plan.md) |
| Выравнивание площадки лагеря | [camp-terrain-leveling-plan.md](Camp/camp-terrain-leveling-plan.md) |
| Фермерская система и идеи строительства | [BUILDER_CONSTRUCTION_IDEA.md](Camp/BUILDER_CONSTRUCTION_IDEA.md) и исходники `camp/Farm*`, `entity/ai/FarmerStandWorkGoal.java` |
| Бой и ИИ стража | [GUARDIAN_REWORK_PLAN.md](Combat/GUARDIAN_REWORK_PLAN.md), [GUARDIAN_STATE_DECISION.md](Combat/GUARDIAN_STATE_DECISION.md) |
| Дефекты движения стража | [guardian-movement-defects-plan.md](Combat/guardian-movement-defects-plan.md) |
| Ограждения: утверждённые требования | [SurvivalPoints_Fences_v1.1.7-final.md](Fences/SurvivalPoints_Fences_v1.1.7-final.md) и [Visual Reference v5](Fences/SurvivalPoints_Fences_Visual_Reference_v5.md) |
| Варианты ржавых секций ограждения | [fence-rusted-variants-plan.md](Fences/fence-rusted-variants-plan.md) |
| Исторические планы по торцам и направленным окончаниям | [fences-torцы-2px-plan.md](Fences/fences-torцы-2px-plan.md), [fences-tip0-directional-tongue-plan.md](Fences/fences-tip0-directional-tongue-plan.md) — сверять статус с утверждённой спецификацией |
| Вкладка креатива и яйца призыва | [CREATIVE_TAB_PLAN.md](Items/CREATIVE_TAB_PLAN.md) |
| Ручные проверки и Forge GameTests | [TESTING.md](Testing/TESTING.md) |
| Художественная концепция | [ART_VISION.md](Art/ART_VISION.md) |
| Стандарты моделей, текстур и UV | [Vanilla-Friendly Guidelines.md](Art/Vanilla-Friendly%20Guidelines.md) |
| Подготовка и утверждение ассетов | [ASSET_WORKFLOW.md](Art/ASSET_WORKFLOW.md) |

## Структура

```
.roo/plans/
├── README.md
├── Architecture/
│   └── PROJECT_VISION.md
├── Art/
│   ├── ART_VISION.md
│   ├── ASSET_WORKFLOW.md
│   └── Vanilla-Friendly Guidelines.md
├── Camp/
│   ├── BUILDER_CONSTRUCTION_IDEA.md
│   ├── CAMP_IDEAS.md
│   ├── DESIGN_CAMP_SOCIAL.md
│   ├── DESIGN_CAMP_SYSTEM.md
│   ├── RADIO_TOWER_DETAIL.md
│   ├── camp-population-plan.md
│   └── camp-terrain-leveling-plan.md
├── Combat/
│   ├── GUARDIAN_REWORK_PLAN.md
│   ├── GUARDIAN_STATE_DECISION.md
│   └── guardian-movement-defects-plan.md
├── Entities/
│   ├── NPC_CAMP_SIMULATION_DESIGN.md
│   ├── npc-names-plan.md
│   └── plan.md
├── Fences/
│   ├── SurvivalPoints_Fences_Visual_Reference_v5.md
│   ├── SurvivalPoints_Fences_v1.1.7-final.md
│   ├── fence-rusted-variants-plan.md
│   ├── fences-tip0-directional-tongue-plan.md
│   └── fences-torцы-2px-plan.md
├── Items/
│   └── CREATIVE_TAB_PLAN.md
├── Roadmap/
│   └── ROADMAP.md
├── Testing/
│   ├── TESTING.md
│   └── log.md
└── _archive/
    ├── LEGACY_FENCES_INVENTORY.md
    ├── LEGACY_fences-textures-32-rewrite-plan.md
    ├── WAVE2_PLAN.md
    ├── chest-uv-fix-plan.md
    ├── fence-legacy-cleanup-plan.md
    ├── fences-v1.1.7-implementation-plan.md
    ├── guardian-movement-defects-plan.full.md
    ├── modular-outpost-plan.md
    └── rules-optimization-plan.md
```

## Источники истины и границы

- **Текущий состав и поведение кода** — исходники в `src/main/java`; AI-контекст — [AI_CONTEXT.md](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md).
- **Порядок работ** — [ROADMAP.md](Roadmap/ROADMAP.md); он не заменяет согласование следующей задачи.
- **NPC** — [Entities/plan.md](Entities/plan.md) для архитектурных контрактов; реализацию сверять с исходниками.
- **Ограждения** — две утверждённые спецификации в `Fences/`; вспомогательные планы не переопределяют их.
- **Идеи** — [CAMP_IDEAS.md](Camp/CAMP_IDEAS.md) и концептуальные документы не являются разрешением на реализацию.
- **Архив** — `_archive/` хранит исторические планы; не считать их активным ТЗ.
- **Тестирование** — [TESTING.md](Testing/TESTING.md) содержит ручные сценарии и имеющиеся автоматические тесты; запуск теста и ручная приёмка — разные результаты.
- При противоречии между кодом и документом описать расхождение. Не менять утверждённое решение без владельца.
