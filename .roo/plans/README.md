# Документация SurvivalPoints — навигатор

> Короткий индекс документации. Читай только нужный документ по типу задачи,
> не открывай все файлы подряд. Канонические источники истины выделены **жирным**.

## Как выбрать документ

| Задача | Читать |
|---|---|
| Общий контекст проекта, состояние кода, правила работы | [`AI_CONTEXT.md`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) |
| Замысел мода, среда, список механик, принципы | [`Architecture/PROJECT_VISION.md`](Architecture/PROJECT_VISION.md) |
| Художественная концепция и визуальное направление | [`Art/ART_VISION.md`](Art/ART_VISION.md) |
| Визуальные и технические стандарты ассетов | [`Art/Vanilla-Friendly Guidelines.md`](Art/Vanilla-Friendly%20Guidelines.md) |
| Процесс подготовки, проверки и утверждения ассетов | [`Art/ASSET_WORKFLOW.md`](Art/ASSET_WORKFLOW.md) |
| Ограждения: спецификация, визуальный референс, план реализации | **`Fences/SurvivalPoints_Fences_v1.1.7-final.md`** и **`Fences/SurvivalPoints_Fences_Visual_Reference_v5.md`** |
| Ограждения: актуальный план правок и история | `Fences/fences-v1.1.7-implementation-plan.md`, `Fences/LEGACY_FENCES_INVENTORY.md` |
| Ограждения: очистка legacy-мусора (`camp_*` удаление, пауза периметра) | **`Fences/fence-legacy-cleanup-plan.md`** |
| Точечные правки ограждений (торцы, направленный язык) | `Fences/fences-torцы-2px-plan.md`, `Fences/fences-tip0-directional-tongue-plan.md` |
| Переход текстур ограждений на 32×32 | `Fences/fences-textures-32-rewrite-plan.md` |
| Кронштейн колючей проволоки | `Camp/barbed-wire-bracket-block-plan.md` |
| Плоские стыки сетки ограждения | `Camp/fence-flat-mesh-joints-plan.md` |
| Модульный аутпост | `Camp/modular-outpost-plan.md` |
| Порядок волн, приоритеты, риски, бюджеты | **`Roadmap/ROADMAP.md`** |
| Архитектура NPC: база, identity UUID, роли | **`Entities/plan.md`** |
| Модель NPC: навыки 0–10, симуляция лагерей | **`Entities/NPC_CAMP_SIMULATION_DESIGN.md`** |
| Система лагерей: модель, блоки, посты, ночь, штат | **`Camp/DESIGN_CAMP_SYSTEM.md`** |
| Соц-эконом слой: глава, торговля, репутация, задания | **`Camp/DESIGN_CAMP_SOCIAL.md`** |
| Радио-вышка (блок, BE, якорь) | `Camp/RADIO_TOWER_DETAIL.md` |
| План волны 2: блоки и генерация | `Camp/WAVE2_PLAN.md` |
| Реворк ИИ стража | `Combat/GUARDIAN_REWORK_PLAN.md` |
| Дефекты движения стража: тряска в углу, «зависание» в ближнем бою | **`Combat/guardian-movement-defects-plan.md`** (актуальный разбор — §7–§9) |
| Решение по `GuardianState` (боевой автомат vs лагерь, утверждено) | `Combat/GUARDIAN_STATE_DECISION.md` |
| Правка UV сундука и брони | `Combat/chest-uv-fix-plan.md` |
| Имена NPC | `Entities/npc-names-plan.md` |
| Вкладка креатива и яйца призыва | `Items/CREATIVE_TAB_PLAN.md` |
| Чек-листы проверки по волнам | `Testing/TESTING.md` |
| Оптимизация правил и режимов Roo Code (завершён) | `rules-optimization-plan.md` |

## Структура

```
.roo/plans/
├── README.md                     — этот навигатор
├── Architecture/                 — общая архитектура и замысел
│   └── PROJECT_VISION.md
├── Art/                          — художественное направление и производство ассетов
│   ├── ART_VISION.md             — визуальная концепция (Vanilla+, силуэт, палитры)
│   ├── Vanilla-Friendly Guidelines.md — стандарты моделей, текстур и UV
│   └── ASSET_WORKFLOW.md         — цикл подготовки, проверки и утверждения
├── Roadmap/                      — порядок разработки
│   └── ROADMAP.md
├── Entities/                     — NPC и сущности
│   ├── plan.md                   — канон архитектуры NPC
│   ├── NPC_CAMP_SIMULATION_DESIGN.md
│   └── npc-names-plan.md
├── Fences/                       — ограждения: спецификация и планы
│   ├── SurvivalPoints_Fences_v1.1.7-final.md      — канон требований
│   ├── SurvivalPoints_Fences_Visual_Reference_v5.md — канон визуала
│   ├── fence-legacy-cleanup-plan.md       — очистка legacy camp_* (выполнено)
│   ├── fences-v1.1.7-implementation-plan.md
│   ├── fences-textures-32-rewrite-plan.md
│   ├── fences-tip0-directional-tongue-plan.md
│   ├── fences-torцы-2px-plan.md
│   └── LEGACY_FENCES_INVENTORY.md — инвентаризация legacy-активов
├── Camp/                         — система лагерей и радиоблок
│   ├── DESIGN_CAMP_SYSTEM.md
│   ├── DESIGN_CAMP_SOCIAL.md
│   ├── RADIO_TOWER_DETAIL.md
│   ├── WAVE2_PLAN.md
│   ├── CAMP_IDEAS.md
│   ├── barbed-wire-bracket-block-plan.md
│   ├── fence-flat-mesh-joints-plan.md
│   └── modular-outpost-plan.md
├── Combat/                       — бой и боевые механики
│   ├── GUARDIAN_REWORK_PLAN.md
│   ├── GUARDIAN_STATE_DECISION.md — решение развилки `GuardianState` (разблокирует R0.1)
│   ├── guardian-movement-defects-plan.md — дефекты движения стража (диагностика + правки)
│   └── chest-uv-fix-plan.md
├── Items/                        — предметы и инвентарь
│   └── CREATIVE_TAB_PLAN.md
├── Testing/                      — тестирование
│   └── TESTING.md
└── rules-optimization-plan.md    — оптимизация правил и режимов Roo Code
```

## Источники истины

- **Художественное направление и стандарты ассетов** — [`Art/ART_VISION.md`](Art/ART_VISION.md) и [`Art/Vanilla-Friendly Guidelines.md`](Art/Vanilla-Friendly%20Guidelines.md). Статус обоих документов — *committed, ожидает утверждения владельцем*; они не являются утверждённым финалом.
- **Ограждения** — [`Fences/SurvivalPoints_Fences_v1.1.7-final.md`](Fences/SurvivalPoints_Fences_v1.1.7-final.md) и [`Fences/SurvivalPoints_Fences_Visual_Reference_v5.md`](Fences/SurvivalPoints_Fences_Visual_Reference_v5.md). Иерархия уточняющих планов описана в [`Fences/LEGACY_FENCES_INVENTORY.md`](Fences/LEGACY_FENCES_INVENTORY.md).
- **Порядок волн и приоритеты** — [`Roadmap/ROADMAP.md`](Roadmap/ROADMAP.md) §2.
- **Архитектура NPC** — [`Entities/plan.md`](Entities/plan.md).
- **Модель NPC и симуляция** — [`Entities/NPC_CAMP_SIMULATION_DESIGN.md`](Entities/NPC_CAMP_SIMULATION_DESIGN.md).
- **Система лагерей** — [`Camp/DESIGN_CAMP_SYSTEM.md`](Camp/DESIGN_CAMP_SYSTEM.md).
- **Соц-эконом слой** — [`Camp/DESIGN_CAMP_SOCIAL.md`](Camp/DESIGN_CAMP_SOCIAL.md).

## Важно

- [`Camp/CAMP_IDEAS.md`](Camp/CAMP_IDEAS.md) — **банк идей**, а не утверждённые задачи. Не реализовывать без согласования.
- Канон Art-документов — только `.roo/plans/Art/`. Устаревшие копии закрыты через `.rooignore`: `assets-source/AiModelling/inbox/approved-art-docs/` (три файла) и `assets-source/AiModelling/VANILLA_FRIENDLY_GUIDELINES.md`.
- `assets-source/` целиком исключён из Git через [`.gitignore`](../../.gitignore). Исходники ассетов локальны и не публикуются; в репозиторий попадают только runtime-ассеты в `src/main/resources`.
- При расхождении документов приоритет у источника истины; при сомнении — спросить владельца.
- Правила работы и стиля — в `.roo/rules/` и `.roo/rules-code/` (загружаются автоматически).
