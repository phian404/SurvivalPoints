# План: очистка legacy-мусора ограждений

## Цель

Полностью отделить работающую актуальную систему ограждений от устаревших legacy-блоков `camp_*`, сохранить генерацию лагеря без обращения к удалённым классам и временно отключить генерацию периметра до отдельной миграции.

Источники истины:

- `.roo/plans/Fences/SurvivalPoints_Fences_v1.1.7-final.md`
- `.roo/plans/Fences/SurvivalPoints_Fences_Visual_Reference_v5.md`
- `.roo/plans/Fences/LEGACY_FENCES_INVENTORY.md`

## Решения владельца

- Удалить legacy-классы `CampWireWebBlock`, `CampFenceBlock`, `CampFencePostBlock`, `CampBarbedWireBlock`, `CampBarbedWireBracketBlock`.
- Удалить все их регистрации, предметы, blockstates, модели, текстуры и loot tables.
- Удалить legacy-привязки к рендереру и локализации.
- Не мигрировать `CampGenerator` в новую систему в этой задаче.
- Временно отключить генерацию периметра в заглушке внутри `generatePerimeter` с явным `TODO` на будущую миграцию.
- Сохранить и не удалять блоки вне legacy-файлов ограждений: `camp_gate`, `camp_radio`, остальные блоки лагеря и актуальные `post` / `*_section`.
- Не менять канонические ассеты новых секций и не исправлять существующие baseline-RATIO замечания валидаторов.

## Актуальная система, которую сохранять

- Java: `PostBlock`, `SectionBlock`, `StackedSectionBlock`, `MeshSectionBlock`, `SheetSectionBlock`, `WireSectionBlock`.
- Block-ID: `post`, `plank_section`, `wire_section`, `mesh_section`, `sheet_section`.
- Модели `post_core`, `section_*`, `section_*_tongue*`.
- Текстуры `plank`, `wire`, `mesh`, `sheet`, `metal` и ванильные ссылки поста.
- Регистрации, item-модели и локализации актуальных block-ID.
- Валидаторы и их исправления для reverse-UV и повёрнутых кубов.

## Шаги очистки

### 1. Отключить legacy-код генератора

В `CampGenerator.generatePerimeter` поставить явную заглушку в начале метода и return:

- оставить вызов `generatePerimeter(level, record)`;
- внутри метода оставить комментарий `TODO` о будущей миграции на `post` и `*_section`;
- не добавлять временную генерацию через новые блоки;
- не оставлять ссылки на удаляемые `CAMP_FENCE*`, `CAMP_BARBED_WIRE*` или legacy-классы в исполнимом коде.

Проверить, что создание радио и лагеря остаются рабочими и генератор не пытается поставить удалённые блоки.

### 2. Удалить legacy-классы Java

Удалить:

- `src/main/java/com/phian404/survivalpoints/camp/block/CampWireWebBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampFenceBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampFencePostBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBracketBlock.java`

Не удалять и не переименовывать `CampGateBlock`, `CampRadioBlock`, `CampRadioBlockEntity` и другие коды лагеря вне перечисленного legacy-набора.

### 3. Очистить регистрацию legacy-блоков

В `ModBlocks` удалить:

- RegistryObject блоков `CAMP_FENCE`, `CAMP_FENCE_POST`, `CAMP_BARBED_WIRE`, `CAMP_WIRE_WEB`, `CAMP_BARBED_WIRE_BRACKET`;
- RegistryObject предметов с теми же legacy-ID;
- все их явные ссылки в комментариях и инициализации регистрации.

Сохранить актуальные регистрации `POST`, `PLANK_SECTION`, `WIRE_SECTION`, `MESH_SECTION`, `SHEET_SECTION`.

### 4. Очистить клиентский рендер

В `ModEntityRenderers.onClientSetup` удалить render-layer для legacy-блоков:

- `CAMP_FENCE`;
- `CAMP_BARBED_WIRE`;
- `CAMP_WIRE_WEB`.

Не трогать существующий cutout-регистр актуальных `WIRE_SECTION` и `MESH_SECTION`, а также render-layer `CAMP_GATE`.

### 5. Удалить legacy-блоковые файлы

Удалить blockstates:

- `blockstates/camp_fence.json`
- `blockstates/camp_fence_post.json`
- `blockstates/camp_barbed_wire.json`
- `blockstates/camp_barbed_wire_bracket.json`
- `blockstates/camp_wire_web.json`

Удалить legacy-модели блока:

- все `models/block/camp_fence*` файлы;
- все `models/block/camp_barbed_wire*` файлы;
- все `models/block/camp_wire_web*` файлы;
- `models/block/camp_fence_section_prototype.json` как сироту прототипа;
- `models/block/camp_fence_side.json` как сироту замены.

Удалить item-модели:

- `models/item/camp_fence.json`
- `models/item/camp_fence_post.json`
- `models/item/camp_barbed_wire.json`
- `models/item/camp_barbed_wire_bracket.json`
- `models/item/camp_wire_web.json`

Удалить loot tables:

- `loot_tables/blocks/camp_fence.json`
- `loot_tables/blocks/camp_fence_post.json`
- `loot_tables/blocks/camp_barbed_wire.json`
- `loot_tables/blocks/camp_wire_web.json`

Loot table кронштейна отсутствует и отдельно не создаётся.

### 6. Удалить legacy-текстуры и связанные сироты

Удалить только текстуры, используемые legacy-набором ограждений:

- `textures/block/camp_fence.png`
- `textures/block/camp_fence_0.png`
- `textures/block/camp_fence_1.png`
- `textures/block/camp_fence_2.png`
- `textures/block/camp_fence_post.png`
- `textures/block/camp_wire_web.png`
- `textures/block/steel_fence_32.png`
- `textures/block/block_steel.png`

Отдельно проверить текстуры `camp_barbed_wire*`: остатков с именами, отличными от блока/модели, не оставлять, если они относятся только к удалённому legacy-блоку.

Не удалять актуальные текстуры `post` и `*_section`, а также общие текстуры вне ограждений.

### 7. Очистить локализации

Удалить из `en_us.json` и `ru_ru.json` записи legacy-ID:

- `camp_fence`;
- `camp_fence_post`;
- `camp_barbed_wire`;
- `camp_barbed_wire_bracket`;
- `camp_wire_web`.

Сохранить записи актуальных `post`, `plank_section`, `wire_section`, `mesh_section`, `sheet_section`.

### 8. Обновить документацию

Вести `LEGACY_FENCES_INVENTORY.md` как завершённый отчёт о выполненной очистке:

- перечислить удалённые классы, регистрации, ассеты и причины удаления;
- зафиксировать временное отключение генерации периметра;
- не описывать удалённые legacy-файлы как текущее состояние кода;
- сохранить ссылку на каноническую спеку и актуальную модель секций.

Обновить `AI_CONTEXT.md`, убрав утверждения о том, что legacy остаётся рабочей зависимостью `CampGenerator`.

Обновить навигационные планы только если ссылки на удалённые legacy-файлы становятся ложными.

### 9. Финальная проверка ссылок

Выполнить точечный поиск по удалённым block-ID и именам классов во всех исходниках и планах:

- в Java не должно остаться исполнимых ссылок на удалённые классы/RegistryObject;
- в ресурсах не должно остаться orphan-ссылок на удалённые JSON-модели и текстуры;
- в lang не должно остаться записей удалённых ID;
- план должен явно различать удалённое legacy-состояние и актуальные модели.

Допустимо оставить упоминания удалённых блоков только в планах как историческое описание или checklist удаления.

## Проверки после реализации

1. `gradlew.bat compileJava --rerun-tasks` — сборка должна завершиться успешно.
2. Поиск по `CAMP_FENCE|CAMP_FENCE_POST|CAMP_BARBED_WIRE|CAMP_BARBED_WIRE_BRACKET|CAMP_WIRE_WEB` в Java — без исполнимых ссылок.
3. Поиск по именам legacy-классов и block-ID во всех ресурсах — без dangling references.
4. `powershell -NoProfile -ExecutionPolicy Bypass -File tools/audit_fence_assets.ps1`.
5. `powershell -NoProfile -ExecutionPolicy Bypass -File tools/check_face_sampling.ps1`.
6. `powershell -NoProfile -ExecutionPolicy Bypass -File tools/check_fence_assembly.ps1`.

Считать cleanup успешным только при отсутствии новых ошибок компиляции и отсутствия dangling legacy-asssets.

## Критерий готовности

- Legacy-классы и legacy-регистрации удалены.
- Legacy-ассеты ограждений удалены.
- `CampGenerator.generatePerimeter` имеет явную заглушку и TODO будущей миграции.
- Актуальные 5 block-ID и новая динамическая логика секций не затронуты.
- Собранные и asset-проверки не имеют новых проблем относительно текущего baseline.
