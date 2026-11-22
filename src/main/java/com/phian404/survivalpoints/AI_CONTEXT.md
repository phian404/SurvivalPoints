# SurvivalPoints — контекст проекта

> Краткий рабочий контекст для AI-помощника и разработчика: технические ограничения, состояние проекта, правила работы, ссылки на подробные планы.
>
> **Концепция и цели:** [`PROJECT_VISION.md`](../../../../../../.roo/plans/Architecture/PROJECT_VISION.md)
> **Общий план:** [`ROADMAP.md`](../../../../../../.roo/plans/Roadmap/ROADMAP.md)
> **Архитектура NPC (канон):** [`plan.md`](../../../../../../.roo/plans/Entities/plan.md)
> **Техническое ТЗ лагерей:** [`DESIGN_CAMP_SYSTEM.md`](../../../../../../.roo/plans/Camp/DESIGN_CAMP_SYSTEM.md)
> **Репутация, торговля и социальные механики:** [`DESIGN_CAMP_SOCIAL.md`](../../../../../../.roo/plans/Camp/DESIGN_CAMP_SOCIAL.md)
> **Модель NPC, навыки и симуляция:** [`NPC_CAMP_SIMULATION_DESIGN.md`](../../../../../../.roo/plans/Entities/NPC_CAMP_SIMULATION_DESIGN.md)
> **Проверки и приёмка:** [`TESTING.md`](../../../../../../.roo/plans/Testing/TESTING.md)

---

## 1. Что такое SurvivalPoints

Постапокалиптический мод для **Minecraft Forge 1.20.1 / Java 17** (зомби-апокалиптические сборки). Самостоятельный мод; интеграции (TacZ и др.) необязательны. Идея — обживание мира выжившими: лагеря, стражи, ресурсы, посты, ночь, радио, торговля, репутация. Страж важен, но долгосрочная центральная механика — **материальные автономные лагеря** (строения, ворота, посты, охрана, ферма, глава, экономика).

Расширяемость: аддоны без изменения исходников (фракции, лагеря/планировки, NPC, блоки, задания, баланс, интеграции). Архитектурная цель; реальные точки расширения вводить по мере появления систем. Простые настройки — данными; сложное — Java-аддон. Не строить сложный API заранее.

Принципы совместимости: базовые механики не требуют TacZ; интеграции изолированы и активируются при наличии мода; число фракций/лагерей не зашито; контент задаётся данными/шаблонами; отсутствие аддона не должно ломать мод.

---

## 2. Среда и технические ограничения

| Параметр | Значение |
|---|---|
| Minecraft | **1.20.1** |
| Forge | **47.4.28** |
| Диапазон версий | `[1.20.1,1.21)` |
| Java | **17** |
| Маппинги | **official (Mojang)** |
| Mod ID | `survivalpoints` |
| Основной пакет | `com.phian404.survivalpoints` |

**Версия 1.20.1 — жёсткое ограничение.** Не использовать API Minecraft/Forge 1.21+.

Сборка на Windows:

```bat
gradlew.bat compileJava
```

Запуск клиента:

```bat
gradlew.bat runClient
```

Перед использованием/изменением Minecraft/Forge API сверяться с MCP-сервером `minecraft-modding` (`inspect-minecraft`, `analyze-symbol`) и/или исходниками Minecraft 1.20.1. **Не угадывать API.**

---

## 3. Карта исходников (ориентир, может устареть)

```
com.phian404.survivalpoints
├── SurvivalPointsMod.java
├── ModConstants.java
├── ModModelLayers.java
├── entity/   BaseSurvivorEntity, BaseSurvivorNpcEntity, BaseGuardianEntity,
│             GuardianMoveControl, ai/, role/ (SurvivorRole, RoleAssignmentService)
├── camp/     CampTuning, CampRecord, CampPost, CampRelation, CampSavedData,
│             CampManager, CampBlueprintParser, CampGenerator;
│             block/CampRadioBlock; blockentity/CampRadioBlockEntity;
│             блоки ограждений (PostBlock, SectionBlock, StackedSectionBlock,
│             MeshSectionBlock, SheetSectionBlock, WireSectionBlock + ржавые варианты)
├── client/   ModEntityRenderers, SurvivorBodyModel, CustomWeaponLayer
└── init/     GuardianTuning, GuardianFeatureFlags, ModEntities, ModBlocks,
              ModCreativeTabs, ModEntityEvents, ModEventBusEvents,
              GuardDebugCommand, CampDebugCommand
```

Перед изменением файла проверять фактическое состояние рабочего дерева.

---

## 4. Текущее состояние проекта

### Страж (`BaseGuardianEntity`) — реворк завершён, приёмка пройдена

Реализованы: автомат состояний ИИ и боевые голы; поиск целей, ближний/дальний бой; стрельба из арбалета с упреждением и компенсацией гравитации; разворот корпуса/головы; сглаживание движения/курса; взвод перед выстрелом; поедание хлеба; паника и призыв союзников; диагностика; система занятого рубежа (NBT).

Ключевые решения:
- **R0.1:** голы — источник истины; `GuardianState`/`DATA_AI_STATE` — диагностический кэш для `/sp_guard`; лагерные состояния волны 3 — данные лагеря (`CampRecord`/`CampPost`) + отдельные голы, `GuardianState` не расширяем; поза арбалета — по предмету в руке, ориентация — по `DATA_FACING_TARGET`.
- **R0.3:** ближний бой на ванильном `MeleeAttackGoal`.
- **R0.4:** еда — флаг `eating` не сериализуется, сторож `eatPulse`, прерывание по цели/урону.
- **Пункт 7:** недостижимая цель — сброс по `noLineOfSightTicks`, память `unreachableTargets`.
- **R0.0:** `requiresUpdateEveryTick()` у четырёх боевых голов (меч, еда, ranged, retreat) — стрельба/отход на 20 Гц. Единственный шаг, оставленный в коде.
- **R0.2 закрыт без выполнения:** самописная физика (`moveDirectly`) остаётся штатной, `CUSTOM_MOVE` включён (регрессии; «зависание в воздухе» — ванильный knockback, см. [`guardian-movement-defects-plan.md`](../../../../../../.roo/plans/Combat/guardian-movement-defects-plan.md)).
- **R0.5 закрыт без выполнения:** `RandomStrollGoal` и контракт флагов `MOVE` (К2) оставлены как есть.

### Система лагерей

Каркас данных: `CampTuning`, `CampRecord`, `CampPost`, `CampRelation`, `CampSavedData`, `CampManager`, `/sp_camp`. Посты пока виртуальные.

**Волна 2 «лагерь как место» начата.** Под-волна 2.1 (якорь+генерация) принята: `CampRadioBlock` + `CampRadioBlockEntity` (`onPlace` создаёт лагерь, `onRemove` распускает, BE привязывается лениво в тике). Под-волна 2.2 (забор+колючая проволока+ворота) на проверке: ворота 1×2 (`CampGateBlock extends DoorBlock`, временные, ночью закрываются); legacy-набор `camp_fence/*` удалён, `CampGenerator.generatePerimeter` — заглушка; актуальная система ограждений (спека v1.1.7-final): `post`, `plank_section`, `wire_section`, `mesh_section`, `sheet_section` (`PostBlock`, `SectionBlock`, `StackedSectionBlock`, `MeshSectionBlock`, `SheetSectionBlock`, `WireSectionBlock`); ржавые вариации `rusted_mesh_0/1`, `rusted_sheet_0/1`, `rusted_wire_0/1`. `CampBlueprintParser` написан, полигон рисуется программно.

Остались: вышка+склад (2.3), электропериметр (2.5, открытые вопросы). Перед продолжением сверить код и [`WAVE2_PLAN.md`](../../../../../../.roo/plans/Camp/WAVE2_PLAN.md). Цель — тестовый лагерь-полигон, один базовый лагерь командой `/sp_camp generate`.

### NPC и роли

`BaseSurvivorEntity` (identity UUID, роль, индекс скина, пол, рост, необязательные camp/post UUID, NBT); `BaseSurvivorNpcEntity` (мирный NPC, назначение роли стражa кликом арбалетом); `BaseGuardianEntity` (страж); `SurvivorRole` (`CIVILIAN`/`GUARDIAN`); `RoleAssignmentService`; два `EntityType` и два яйца призыва; общий рендерер. Имя NPC постоянное: `ensureName()` из `SurvivorNames`, хранится в `CustomName` ([`npc-names-plan.md`](../../../../../../.roo/plans/Entities/npc-names-plan.md)). Модель понятий — [`NPC_CAMP_SIMULATION_DESIGN.md`](../../../../../../.roo/plans/Entities/NPC_CAMP_SIMULATION_DESIGN.md) §1.1; профессии и лидерство не реализованы.

### Геометрия торса и броня (этап 3 — реализовано)

`SurvivorBodyModel`: меш из `PlayerModel.createMesh(CubeDeformation.NONE, slim)`. Четыре child-сегмента `chest_seg0..3` (body) + два капа `chest_under0..1`. Параметры формы — константы в `SurvivorChestTuning`; команда `/spdebug chest` удалена. UV: `texOffs` сдвигается на `width` за сегмент; верхняя грань `DOWN` отключена; нижняя рисуется капами. Выступ виден только при `SurvivorGender.FEMALE` и скрывается при броне в `CHEST`. Броня — ванильный `HumanoidArmorLayer` (`PLAYER_INNER_ARMOR`/`PLAYER_OUTER_ARMOR`), добавлен до `CustomWeaponLayer`. Слои моделей — `ModModelLayers.SURVIVOR_BODY` / `SURVIVOR_BODY_SLIM`.

### Скины NPC

`assets-source/ReadyToUseSkins/`: 14 обычных NPC + 4 лидера; черновой лор — [`SurvivalPoints_Leaders.md`](../../../../../../assets-source/ReadyToUseSkins/SurvivalPoints_Leaders.md). Скопированы в `src/main/resources/assets/survivalpoints/textures/entity/skin/` (14 PNG), используются через `SurvivorSkin.VARIANTS` и `SurvivorSkin.textureFor`. Визуальная проверка в игре не выполнена. Известное расхождение: `Leader_Martha_Hil_female_0.png` (`Hil` вместо `Hill`) — не переименовывать.

### Текущий приоритет

Реворк стража завершён. Волна 2 начата (2.1 принята, 2.2 на проверке). Не переключаться самовольно на фракции/аддоны/новый реворк стража; не начинать крупный рефакторинг без согласования. Не реализовывать идеи из концепции без согласования; если задача неясна — один короткий уточняющий вопрос.

---

## 5. Правила работы

Диагностика: `/sp_guard [радиус]`, `/sp_camp`, `/sp_perf` (если присутствует). Лог клиента: `run/logs/latest.log`. Формат и проверки этапов: [`TESTING.md`](../../../../../../.roo/plans/Testing/TESTING.md). Команды могут измениться — сверять с исходниками.

---

## 6. Проектирование будущих систем и аддонов

- Не привязывать механику к стартовым четырём фракциям; новая фракция использует общие механики.
- Повторное использование вместо копирования; отличия описывать данными/шаблонами.
- Не смешивать уровни расширения: конфигурация/данные, общие механики, Java API. Выбирать самый простой надёжный механизм.
- Интеграции с оружейными модами — отдельный необязательный слой; проверять наличие мода предусмотренным для Forge способом, не ссылаться на его классы из обязательного кода.

---

## 7. Документы проекта

Все в `.roo/plans/` (и `assets-source/`).

- [`PROJECT_VISION.md`](../../../../../../.roo/plans/Architecture/PROJECT_VISION.md) — концепция и цели
- [`ROADMAP.md`](../../../../../../.roo/plans/Roadmap/ROADMAP.md) — канон порядка волн и приоритетов
- [`plan.md`](../../../../../../.roo/plans/Entities/plan.md) — канон архитектуры NPC
- [`DESIGN_CAMP_SYSTEM.md`](../../../../../../.roo/plans/Camp/DESIGN_CAMP_SYSTEM.md) — устройство лагерей
- [`DESIGN_CAMP_SOCIAL.md`](../../../../../../.roo/plans/Camp/DESIGN_CAMP_SOCIAL.md) — репутация, торговля, глава, задания
- [`NPC_CAMP_SIMULATION_DESIGN.md`](../../../../../../.roo/plans/Entities/NPC_CAMP_SIMULATION_DESIGN.md) — модель NPC, навыки 0–10, симуляция
- [`RADIO_TOWER_DETAIL.md`](../../../../../../.roo/plans/Camp/RADIO_TOWER_DETAIL.md) — радио-вышка
- [`WAVE2_PLAN.md`](../../../../../../.roo/plans/Camp/WAVE2_PLAN.md) — план волны 2
- [`GUARDIAN_REWORK_PLAN.md`](../../../../../../.roo/plans/Combat/GUARDIAN_REWORK_PLAN.md) — план реворка ИИ стража
- [`GUARDIAN_STATE_DECISION.md`](../../../../../../.roo/plans/Combat/GUARDIAN_STATE_DECISION.md) — решение по `GuardianState` (вариант A)
- [`TESTING.md`](../../../../../../.roo/plans/Testing/TESTING.md) — проверки и приёмка
- [`CREATIVE_TAB_PLAN.md`](../../../../../../.roo/plans/Items/CREATIVE_TAB_PLAN.md) — вкладка креатива и яйца призыва
- [`CAMP_IDEAS.md`](../../../../../../.roo/plans/Camp/CAMP_IDEAS.md) — банк идей (не утверждённый план)
- [`SurvivalPoints_Leaders.md`](../../../../../../assets-source/ReadyToUseSkins/SurvivalPoints_Leaders.md) — черновой лор лидеров
- [`guardian-movement-defects-plan.md`](../../../../../../.roo/plans/Combat/guardian-movement-defects-plan.md) — разбор «зависания в воздухе»
- [`SurvivalPoints_Fences_v1.1.7-final.md`](../../../../../../.roo/plans/Fences/SurvivalPoints_Fences_v1.1.7-final.md), [`fence-legacy-cleanup-plan.md`](../../../../../../.roo/plans/Fences/fence-legacy-cleanup-plan.md), [`fence-rusted-variants-plan.md`](../../../../../../.roo/plans/Fences/fence-rusted-variants-plan.md) — ограждения
- [`npc-names-plan.md`](../../../../../../.roo/plans/Entities/npc-names-plan.md) — имена NPC

Если документы противоречат друг другу — указать противоречие и попросить владельца определить приоритетный источник. Идея не считается утверждённым решением, пока владелец явно не подтвердил.

---

## 8. Главное правило

**Сначала понять текущую задачу и состояние проекта, затем сделать минимальное проверяемое изменение, проверить его и обновить документацию.** Не угадывать API, не переключать приоритеты самостоятельно и не реализовывать идеи из концепции без согласования.
