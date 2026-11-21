# Инвентаризация legacy-ограждений SurvivalPoints

> **Статус: ВЫПОЛНЕНО.** Legacy-набор удалён из проекта целиком —
> см. план [`fence-legacy-cleanup-plan.md`](fence-legacy-cleanup-plan.md).
> Документ ниже сохранён как исторический отчёт: он описывает, что именно
> было удалено и почему.

> **ВНИМАНИЕ ДЛЯ ВСЕХ ЧАТОВ И АГЕНТОВ.**
> Всё, что перечислено в этом документе, — **устаревшая (legacy) реализация**.
> Не использовать как образец, не расширять, не «улучшать», не копировать паттерны.
>
> Единственный источник истины по дизайну ограждений:
> [`SurvivalPoints_Fences_v1.1.7-final.md`](.roo/plans/Fences/SurvivalPoints_Fences_v1.1.7-final.md) —
> основной документ (требования, геометрия, коллизии, состояния) и
> [`SurvivalPoints_Fences_Visual_Reference_v5.md`](.roo/plans/Fences/SurvivalPoints_Fences_Visual_Reference_v5.md) —
> визуальный референс к нему.
>
> Иерархия остальных документов пакета:
>
> | Роль | Документ |
> |---|---|
> | Основной документ | [`SurvivalPoints_Fences_v1.1.7-final.md`](.roo/plans/Fences/SurvivalPoints_Fences_v1.1.7-final.md) |
> | Визуальный референс | [`SurvivalPoints_Fences_Visual_Reference_v5.md`](.roo/plans/Fences/SurvivalPoints_Fences_Visual_Reference_v5.md) |
> | План внедрения (статус шапки отменён, см. ниже) | [`fences-v1.1.7-implementation-plan.md`](.roo/plans/Fences/fences-v1.1.7-implementation-plan.md) |
> | Принятое изменение: `tip = 0` + направленный язык | [`fences-tip0-directional-tongue-plan.md`](.roo/plans/Fences/fences-tip0-directional-tongue-plan.md) — **отменяет** раздел `tip = 4` плана внедрения |
> | Открытый вопрос: 32×32 текстуры | [`fences-textures-32-rewrite-plan.md`](.roo/plans/Fences/fences-textures-32-rewrite-plan.md) — конфликт 16×16/32×32 **не решён**, см. §2 этого документа |
> | Точечная правка торцов | [`fences-torцы-2px-plan.md`](.roo/plans/Fences/fences-torцы-2px-plan.md) |
> | Историческая инвентаризация legacy | этот документ |

## Что сделано при очистке

- Удалены классы `CampFenceBlock`, `CampFencePostBlock`, `CampBarbedWireBlock`,
  `CampBarbedWireBracketBlock`, `CampWireWebBlock`.
- Удалены их регистрации блоков и предметов в `ModBlocks`, а также render-layer
  в `ModEntityRenderers`.
- Удалены blockstates, block-модели (включая сироты), item-модели, loot tables
  и локализационные записи legacy-ID.
- `CampGenerator.generatePerimeter` заменён заглушкой с `TODO`: периметр
  временно не генерируется и будет переписан на `post` / `*_section`.
- `camp_gate` и `camp_radio` сохранены — они не относятся к legacy-ограждениям.

## Legacy-блоки (удалены)

Файлы классов удалены — ссылки на исходники намеренно не даются.

| Block-ID | Бывший класс | Свойства | Статус |
|---|---|---|---|
| `camp_fence` | `CampFenceBlock` extends `IronBarsBlock` | `NORTH/EAST/SOUTH/WEST` + `WATERLOGGED` | удалён |
| `camp_fence_post` | `CampFencePostBlock` extends `IronBarsBlock` | `NORTH/EAST/SOUTH/WEST` + `TOP` + `WATERLOGGED` | удалён |
| `camp_barbed_wire` | `CampBarbedWireBlock` extends `Block` | `NORTH/EAST/SOUTH/WEST` | удалён |
| `camp_barbed_wire_bracket` | `CampBarbedWireBracketBlock` extends `HorizontalDirectionalBlock` | `FACING` | удалён, в новой системе отсутствует |
| `camp_wire_web` | `CampWireWebBlock` | тестовый | удалён, тестовая заглушка |

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

## Известные дефекты legacy (историческая справка)

Все перечисленные файлы удалены; дефекты сохранены только как объяснение,
почему система была заменена, а не починена.

| Объект | Дефект |
|---|---|
| `camp_fence_post_ends.json` | Второй элемент `from [7,0,0] to [9,16,16]` — балка на всю длину блока вместо торцевого стержня 2×2×2; `cullface` на всех гранях; UV `[0,0,2,16]` на `north`. Источник «растянутых боковых торцов» |
| `camp_fence_mesh.json` | Плоскость `Z 0..8` при UV `[0,0,16,16]` — искажение 1:2. Источник «растянутой сетки» |
| `camp_fence.png` | Файл 0 байт (битый), но на него ссылались модели |
| `camp_barbed_wire.png` | Файл отсутствовал, но на него ссылались `camp_barbed_wire_side` и `camp_barbed_wire_post` |
| `camp_fence.json` (blockstate) | Для `north=true` без соседей применялись `mesh y=0` + `mesh y=180` + `post_ends y=180` одновременно |
| `camp_barbed_wire_side.json` | Нити на `Y 0..1` и `7.5..8.5` вместо `4..6` и `12..14`; диагонали с `rescale` до `Z 11.314` |
| `camp_fence_post_beam.json` | Стойка 4×4 (`6..10`) вместо 8×8 (`4..12`) |

## Файлы legacy (удалены)

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

## Точки использования в коде (были удалены)

| Файл | Что удалено |
|---|---|
| `ModBlocks` | Регистрация блоков и предметов legacy |
| `ModCreativeTabs` | Упоминание скрытого legacy-набора |
| `CampGenerator` | Тело `generatePerimeter` заменено заглушкой с `TODO` |
| `CampBarbedWireBracketBlock` | Проверка установки на `CAMP_FENCE` / `CAMP_BARBED_WIRE` (класс удалён) |

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
