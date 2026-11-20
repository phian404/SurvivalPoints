# Инвентаризация legacy-ограждений SurvivalPoints

> **ВНИМАНИЕ ДЛЯ ВСЕХ ЧАТОВ И АГЕНТОВ.**
> Всё, что перечислено в этом документе, — **устаревшая (legacy) реализация**.
> Не использовать как образец, не расширять, не «улучшать», не копировать паттерны.
>
> Единственный источник истины по дизайну ограждений:
> [`SurvivalPoints_Fences_v1.1.7-final.md`](.roo/plans/fences/SurvivalPoints_Fences_v1.1.7-final.md)
> и [`SurvivalPoints_Fences_Visual_Reference_v5.md`](.roo/plans/fences/SurvivalPoints_Fences_Visual_Reference_v5.md).
>
> План внедрения новой системы: [`fences-v1.1.7-implementation-plan.md`](.roo/plans/fences/fences-v1.1.7-implementation-plan.md).

## Статус

Legacy-блоки **остаются зарегистрированными и работоспособными**, потому что
[`CampGenerator`](src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java:65)
ставит именно их при генерации лагеря. Они **скрыты из креативной вкладки** и
не подлежат развитию. Удаление — отдельная волна после миграции генератора.

## Legacy-блоки

| Block-ID | Класс | Свойства | Статус |
|---|---|---|---|
| `camp_fence` | [`CampFenceBlock`](src/main/java/com/phian404/survivalpoints/camp/block/CampFenceBlock.java:30) extends `IronBarsBlock` | `NORTH/EAST/SOUTH/WEST` + `WATERLOGGED` | deprecated |
| `camp_fence_post` | [`CampFencePostBlock`](src/main/java/com/phian404/survivalpoints/camp/block/CampFencePostBlock.java:40) extends `IronBarsBlock` | `NORTH/EAST/SOUTH/WEST` + `TOP` + `WATERLOGGED` | deprecated |
| `camp_barbed_wire` | [`CampBarbedWireBlock`](src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBlock.java:37) extends `Block` | `NORTH/EAST/SOUTH/WEST` | deprecated |
| `camp_barbed_wire_bracket` | [`CampBarbedWireBracketBlock`](src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBracketBlock.java:14) extends `HorizontalDirectionalBlock` | `FACING` | deprecated, в новой системе отсутствует |
| `camp_wire_web` | [`CampWireWebBlock`](src/main/java/com/phian404/survivalpoints/camp/block/CampWireWebBlock.java:1) | тестовый | deprecated, тестовая заглушка |

## Почему legacy непригоден как образец

1. **Направленные свойства у опоры.** `CampFencePostBlock` наследует `IronBarsBlock`
   и имеет `NORTH/EAST/SOUTH/WEST` — прямо запрещено D-09 спеки
   («металлические муфты/крепления на опорах отклонены навсегда»).
2. **Multipart вместо variants.** Опоры и секции собираются из multipart-частей по
   сторонам, тогда как спека требует `AXIS` у секций и простые `variants` у опоры.
3. **Соединение через `IronBarsBlock.attachsTo`.** В спеке соединение секций
   определяется `canSurvive` по оси, а не автоматическим примыканием к любому
   `IronBarsBlock`.
4. **Кронштейн как отдельный блок.** В спеке креплений нет: проволока —
   самостоятельная секция `wire_section`.
5. **Ручные JSON без datagen.** Все blockstates/модели legacy написаны вручную и
   содержат дефекты (см. ниже).

## Известные дефекты legacy (не исправляются, система заменяется)

| Объект | Дефект |
|---|---|
| [`camp_fence_post_ends.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_post_ends.json:20) | Второй элемент `from [7,0,0] to [9,16,16]` — балка на всю длину блока вместо торцевого стержня 2×2×2; `cullface` на всех гранях; UV `[0,0,2,16]` на `north`. Источник «растянутых боковых торцов» |
| [`camp_fence_mesh.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_mesh.json:9) | Плоскость `Z 0..8` при UV `[0,0,16,16]` — искажение 1:2. Источник «растянутой сетки» |
| [`camp_fence.png`](src/main/resources/assets/survivalpoints/textures/block/camp_fence.png) | Файл 0 байт (битый), но на него ссылаются модели |
| `camp_barbed_wire.png` | Файл отсутствует, но на него ссылаются `camp_barbed_wire_side` и `camp_barbed_wire_post` |
| [`camp_fence.json`](src/main/resources/assets/survivalpoints/blockstates/camp_fence.json:7) | Для `north=true` без соседей применяются `mesh y=0` + `mesh y=180` + `post_ends y=180` одновременно |
| [`camp_barbed_wire_side.json`](src/main/resources/assets/survivalpoints/models/block/camp_barbed_wire_side.json:9) | Нити на `Y 0..1` и `7.5..8.5` вместо `4..6` и `12..14`; диагонали с `rescale` до `Z 11.314` |
| [`camp_fence_post_beam.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_post_beam.json:8) | Стойка 4×4 (`6..10`) вместо 8×8 (`4..12`) |

## Файлы legacy

### Java

- `src/main/java/com/phian404/survivalpoints/camp/block/CampFenceBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampFencePostBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampBarbedWireBracketBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/block/CampWireWebBlock.java`

### Blockstates

- `src/main/resources/assets/survivalpoints/blockstates/camp_fence.json`
- `src/main/resources/assets/survivalpoints/blockstates/camp_fence_post.json`
- `src/main/resources/assets/survivalpoints/blockstates/camp_barbed_wire.json`
- `src/main/resources/assets/survivalpoints/blockstates/camp_barbed_wire_bracket.json`
- `src/main/resources/assets/survivalpoints/blockstates/camp_wire_web.json`

### Модели block

Подключённые:

- `camp_fence_mesh.json`, `camp_fence_mesh_center.json`
- `camp_fence_post_ends.json`, `camp_fence_post_center.json`, `camp_fence_post_beam.json`, `camp_fence_post_rib.json`, `camp_fence_post.json`
- `camp_barbed_wire_side.json`, `camp_barbed_wire_post.json`, `camp_barbed_wire.json`
- `camp_barbed_wire_bracket.json`

Сироты (ни к чему не подключены):

- `camp_fence_0.json`, `camp_fence_1.json`, `camp_fence_2.json`
- `camp_fence_end_north.json`
- `camp_fence_section_prototype.json`
- `camp_fence_side.json`

### Модели item

- `camp_fence.json`, `camp_fence_post.json`, `camp_barbed_wire.json`,
  `camp_barbed_wire_bracket.json`, `camp_wire_web.json`

### Текстуры

- `camp_fence.png` (0 байт — битая)
- `camp_fence_0.png`, `camp_fence_1.png`, `camp_fence_2.png` (сироты)
- `camp_fence_post.png`
- `steel_fence_32.png`
- `block_steel.png`
- `camp_wire_web.png`

### Лут-таблицы

- `data/survivalpoints/loot_tables/blocks/camp_fence.json`
- `data/survivalpoints/loot_tables/blocks/camp_fence_post.json`
- `data/survivalpoints/loot_tables/blocks/camp_barbed_wire.json`

## Точки использования в коде

| Файл | Строка | Использование |
|---|---|---|
| [`ModBlocks`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java:27) | 27–44 | Регистрация блоков legacy |
| [`ModBlocks`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java:53) | 53–75 | Регистрация предметов legacy |
| [`ModCreativeTabs`](src/main/java/com/phian404/survivalpoints/init/ModCreativeTabs.java:20) | 20–24 | Вывод в креативную вкладку — **скрыто** |
| [`CampGenerator`](src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java:65) | 65–69 | Установка блоков при генерации лагеря — **единственный потребитель, менять нельзя в волне 1** |
| `CampBarbedWireBracketBlock` | 36–39 | Проверка установки на `CAMP_FENCE` / `CAMP_BARBED_WIRE` |

## Что в новой системе отсутствует

- Кронштейн колючей проволоки как отдельный блок.
- Блок `camp_wire_web` (тестовая заглушка).
- Направленные свойства `NORTH/EAST/SOUTH/WEST` у опоры.
- Наследование `IronBarsBlock`.
- Multipart-модели для секций и опоры.
- Крепления, муфты, хомуты, пластины, болты на опорах.

## Новые block-id (взамен)

| Legacy | Замена |
|---|---|
| `camp_fence_post` | `post` |
| `camp_fence` | `plank_section` / `mesh_section` / `sheet_section` |
| `camp_barbed_wire` | `wire_section` |
| `camp_barbed_wire_bracket` | — (отсутствует по спеке) |
| `camp_wire_web` | — (отсутствует по спеке) |
