# План: внедрение модульных ограждений по спеке v1.1.7-final (волна 1)

> ## ⚠️ Раздел отменён: принято `tip = 0` + направленный язык
>
> Текст ниже сохранён как история решения. **Действующее решение** —
> [`fences-tip0-directional-tongue-plan.md`](.roo/plans/Fences/fences-tip0-directional-tongue-plan.md):
> `tip = 4` отменён как источник Z-fighting на стыках, возвращается `tip = 0`
> из спеки плюс направленный «язык» (`section_*_tongue.json`) только в сторону
> [`PostBlock`](../src/main/java/com/phian404/survivalpoints/camp/block/fence/PostBlock.java:46).
>
> ~~Спека v1.1.7-final требует `tip = 0` (элементы строго `0..16` по оси).
> В реализации принято `tip = 4`: секции занимают `-4..20` по оси пролёта.~~
>
> Причина отмены: при `tip = 4` выступ секции попадает в одну плоскость с гранью
> плотного ванильного блока и с выступом соседней секции — возникает Z-fighting,
> читаемый как «чужая текстура». Подробный разбор — в плане `tip0`.
>
> Что остаётся неизменным:
> - Коллизия (D-08) **не менялась**: `box(7,0,0,9,16,16)` / `box(0,0,7,16,16,9)`.
> - `canSurvive` сохраняет прежнюю семантику.

> **Статус: код и ассеты реализованы, сборка проходит (BUILD SUCCESSFUL).**
> Остались шаги, требующие запуска клиента: проверка загрузки мира, скриншот-тест
> разрешений и игровая проверка визуала.
>
> Что уже в проекте:
> - [`PostBlock`](../../src/main/java/com/phian404/survivalpoints/camp/block/fence/PostBlock.java:39)
> - [`SectionBlock`](../../src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:44)
> - регистрация 5 блоков и предметов в [`ModBlocks`](../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java:46)
> - модели `post_core`, `section_wire`, `section_mesh`, `section_sheet`, `section_planks`
> - модели `section_*_tongue` (направленный язык к опоре, см. `fences-tip0-directional-tongue-plan.md`)
> - текстуры `plank`, `wire`, `mesh`, `sheet`, `metal` — все **16×16**, подтверждено инструментально (см. ниже)
> - blockstates по `WATERLOGGED` / `AXIS`, item-модели, lang en/ru
> - render type через `ItemBlockRenderTypes` (конвенция проекта)
> - legacy `camp_*` скрыт из креативной вкладки

**Разрешение текстур — решено: 16×16 (`strict_16`).**

Проверка фактических файлов (`System.Drawing`, все PNG в `textures/block/`) даёт:

| Файл | Размер | Роль |
|---|---|---|
| `plank.png` | 16×16 | доски секции |
| `wire.png` | 16×16 | нити колючей проволоки |
| `mesh.png` | 16×16 | сетка-рабица |
| `sheet.png` | 16×16 | листовая панель |
| `metal.png` | 16×16 | рейка/скоба |
Вариант 32×32 из [`fences-textures-32-rewrite-plan.md`](.roo/plans/Fences/fences-textures-32-rewrite-plan.md) **отклонён**: при UV-окнах `0..16` и плотности 16 px/блок текстура 32×32 либо сэмплируется вчетверо мельче (диссонанс с ванильной опорой), либо требует UV `0..32` с пересчётом всех девяти моделей. Стандарт `strict_16` из Art-гайдлайнов и §6.3 спеки соблюдён без отклонений.

**Опора — только ванильные текстуры (без отклонений).** §6.2 и §6.3 спеки прямо предписывают `minecraft:block/stripped_oak_log` и `minecraft:block/stripped_oak_log_top`: всего 5 собственных PNG + 2 ванильные ссылки. Ранее здесь было самовольно введено отклонение — собственные `post_wood.png` / `post_wood_top.png`. Оно **отменено и откачено**: файлы удалены, [`post_core.json`](src/main/resources/assets/survivalpoints/models/block/post_core.json:1) снова ссылается на ванильные текстуры. Причина отката — прямое нарушение `vanilla_plus`: самодельная грубая древесина проигрывала ванильному обтёсанному бревну и разрушала стилистическое единство. Форма колонны (`4..12` по X/Z, `0..16` по Y) не менялась.

UV колонны приведены к ванильному эталону [`fence_post.json`](assets-source/AiModelling/vanilla-reference/1.20.1/assets/minecraft/models/block/fence_post.json:1): бока `[4,0,12,16]`, торцы `[4,4,12,12]` — масштаб 1:1 с мировым пикселем, а не растяжение на всю текстуру.

Источник истины по дизайну: [`SurvivalPoints_Fences_v1.1.7-final.md`](.roo/plans/Fences/SurvivalPoints_Fences_v1.1.7-final.md) и [`SurvivalPoints_Fences_Visual_Reference_v5.md`](.roo/plans/Fences/SurvivalPoints_Fences_Visual_Reference_v5.md). Иерархия остальных документов пакета — в [`LEGACY_FENCES_INVENTORY.md`](.roo/plans/Fences/LEGACY_FENCES_INVENTORY.md).

## Итоги аудита текущего состояния

Аудит проведён по исходникам. Текущая реализация архитектурно расходится со спекой и содержит прямые дефекты.

### Дефекты текущих ограждений

| # | Объект | Дефект |
|---|---|---|
| 1 | [`camp_fence_post_ends.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_post_ends.json:20) | Второй элемент `from [7,0,0] to [9,16,16]` — балка на всю длину блока вместо торцевого стержня 2×2×2. Плюс `cullface` на всех гранях и UV `[0,0,2,16]` на `north`. Причина «растянутых боковых торцов» |
| 2 | [`camp_fence_mesh.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_mesh.json:9) | Плоскость `Z 0..8` (половина пролёта) с UV `[0,0,16,16]` — искажение 1:2. Причина «растянутой по вертикали сетки» |
| 3 | [`camp_fence.png`](src/main/resources/assets/survivalpoints/textures/block/camp_fence.png) | Файл 0 байт (битый). На него ссылаются `camp_fence_mesh`, `camp_fence_side`, item-модель `camp_fence` |
| 4 | `camp_barbed_wire.png` | Файл отсутствует. На него ссылаются `camp_barbed_wire_side` и `camp_barbed_wire_post` |
| 5 | [`camp_fence.json`](src/main/resources/assets/survivalpoints/blockstates/camp_fence.json:7) | Для `north=true` без соседей применяются `mesh y=0`, `mesh y=180` и `post_ends y=180` одновременно — панель занимает весь блок и уходит в несоединённую сторону |
| 6 | [`camp_barbed_wire_side.json`](src/main/resources/assets/survivalpoints/models/block/camp_barbed_wire_side.json:9) | Нити на `Y 0..1` и `7.5..8.5` вместо `4..6` и `12..14`; диагонали с `rescale` до `Z 11.314` выходят за половину блока |
| 7 | [`camp_fence_post_beam.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_post_beam.json:8) | Стойка 4×4 (`6..10`) вместо 8×8 (`4..12`) по спеке v1.1.7 |
| 8 | [`CampFencePostBlock`](src/main/java/com/phian404/survivalpoints/camp/block/CampFencePostBlock.java:40) | Наследует `IronBarsBlock`, имеет направленные свойства и `camp_fence_post_rib` — прямо запрещено D-09 спеки |

### Сироты (не подключены ни к одному blockstate)

- [`camp_fence_0.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_0.json:1), [`camp_fence_1.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_1.json:1), [`camp_fence_2.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_2.json:1)
- [`camp_fence_end_north.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_end_north.json:1)
- [`camp_fence_section_prototype.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_section_prototype.json:1)
- [`camp_fence_side.json`](src/main/resources/assets/survivalpoints/models/block/camp_fence_side.json:1)

### Отсутствие инфраструктуры спеки

- Datagen отсутствует: `GatherDataEvent` / `DataGenerator` / `BlockStateProvider` в `src/main/java` не найдены. Все JSON создаются вручную.
- Спека требует 5 block-id (`post`, `plank_section`, `wire_section`, `mesh_section`, `sheet_section`), 18 состояний, свойство `AXIS` у секций, `canSurvive`-логику. В проекте 3 блока на `IronBarsBlock` с направленными свойствами.

## Принятые решения (согласовано с заказчиком)

- **R-01.** Новая система из 5 блоков строится **параллельно**; legacy `camp_*` остаются зарегистрированными и работающими, так как [`CampGenerator`](src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java:65) ставит именно их.
- **R-02.** Legacy `camp_fence`, `camp_fence_post`, `camp_barbed_wire`, `camp_barbed_wire_bracket` **скрываются из креативной вкладки** и помечаются deprecated.
- **R-03.** Кронштейн и текущая `camp_barbed_wire` в новой архитектуре **не воспроизводятся**: проволока — самостоятельная секция `wire_section` с двумя нитями; крепления на опорах запрещены спекой (D-09, «отклонено навсегда»).
- **R-04.** Каскад разрушения опор (§5.1 спеки) **вынесен за пределы этой волны** — отдельная волна после подтверждения визуала и базовой логики.
- **R-05.** Текстуры — **16×16** для всех своих файлов (1:1 с ванильной опорой, минимум расхождений плотности). Повышение разрешения — точечно по результату скриншот-теста.
- **R-06.** `tip = 0`: выступов за границу блока нет; UV `0..16` = полная ширина текстуры; зазор 4 px у опоры — принятое ограничение v1.

## Ограничения волны

- Только Forge 1.20.1 / Java 17.
- Никаких `BlockEntity`, тиков, NBT, GUI.
- Никакой Java-логики «на материал/комбинацию»: один класс `SectionBlock` на 4 block-id.
- Не менять [`CampGenerator`](src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java:65) в этой волне.
- Не удалять legacy-файлы — только пометить и скрыть.
- Datagen не внедрять: JSON создаются статикой по таблице 6.2 спеки.

## Пошаговый план

### Шаг 1. Инвентаризация legacy

Создать [`LEGACY_FENCES_INVENTORY.md`](.roo/plans/Fences/LEGACY_FENCES_INVENTORY.md) с перечнем:

- 4 legacy-блока: `camp_fence`, `camp_fence_post`, `camp_barbed_wire`, `camp_barbed_wire_bracket`;
- их классы, blockstates, модели (включая сироты), текстуры (включая битые и отсутствующие), item-модели;
- точки использования: [`ModBlocks`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java:27), [`ModCreativeTabs`](src/main/java/com/phian404/survivalpoints/init/ModCreativeTabs.java:20), [`CampGenerator`](src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java:65);
- пометку «не использовать в новых задачах» и ссылку на спеку v1.1.7-final как единственный источник истины.

### Шаг 2. Скрыть legacy из креативной вкладки

В [`ModCreativeTabs`](src/main/java/com/phian404/survivalpoints/init/ModCreativeTabs.java:20) убрать 4 вызова `output.accept` для `CAMP_FENCE_ITEM`, `CAMP_FENCE_POST_ITEM`, `CAMP_BARBED_WIRE_ITEM`, `CAMP_BARBED_WIRE_BRACKET_ITEM`. Регистрация блоков и предметов в [`ModBlocks`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java:27) сохраняется.

### Шаг 3. Регистрация 5 новых block-id

В [`ModBlocks`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java:20) добавить:

| Block-ID | Класс | Свойства | Состояний |
|---|---|---|---|
| `post` | `PostBlock` | `WATERLOGGED` | 2 |
| `plank_section` | `SectionBlock` | `AXIS` × `WATERLOGGED` | 4 |
| `wire_section` | `SectionBlock` | `AXIS` × `WATERLOGGED` | 4 |
| `mesh_section` | `SectionBlock` | `AXIS` × `WATERLOGGED` | 4 |
| `sheet_section` | `SectionBlock` | `AXIS` × `WATERLOGGED` | 4 |

Итого 18 состояний. Для каждого — `BlockItem`.

### Шаг 4. `PostBlock`

Новый класс. Требования:

- Колонна 8×8 px: `getShape` / `getCollisionShape` = `box(4,0,4,12,16,12)`.
- Установка: на твёрдую поверхность или на `post`; на секцию — запрещена.
- Вертикальное стекирование без ограничения высоты.
- `SimpleWaterloggedBlock`, `noOcclusion`, opacity 0, `SoundType.WOOD`.
- **Направленных свойств нет** (D-09). Модель — `variants` по `WATERLOGGED`, без multipart.
- Каскад разрушения в этой волне **не реализуется**.

### Шаг 5. `SectionBlock`

Один класс на 4 block-id. Требования:

- Свойство `AXIS` (`x`/`z`) по горизонтальной ориентации игрока при установке.
- `canSurvive`: вдоль `AXIS` хотя бы с одной стороны на том же `Y` сосед = `post` или `*_section`.
- `neighborChanged`: при `canSurvive == false` → `destroyBlock(pos, true)` только на сервере.
- Вертикальных ограничений нет (D-18); секции не обрушаются при сносе опор (D-05).
- Коллизия по D-08: ось Z — `box(7,0,0,9,16,16)`; ось X — `box(0,0,7,16,16,9)`.
- `SimpleWaterloggedBlock`, `noOcclusion`, opacity 0.
- `SoundType`: `plank_section` — дерево; `wire_section` / `mesh_section` / `sheet_section` — металл.
- Материал (текстура/звук/коллизия) задаётся через конструктор, без подклассов на материал.

### Шаг 6. Модели block JSON (5 файлов)

По таблице 6.2 спеки, локальные координаты 0..16, секция вдоль Z, `tip = 0`.
Фактические файлы (проверено чтением JSON):

| Модель | Элемент | X | Y | Z | Текстура |
|---|---|---|---|---|---|
| `post_core` | колонна | 4..12 | 0..16 | 4..12 | `post_wood`, торцы — `post_wood_top` |
| `section_wire` | нити (2 шт) | 7..9 | 4..6, 12..14 | 0..16 | `wire` |
| `section_mesh` | панель | 7..9 | 0..16 | 0..16 | `mesh` |
| `section_sheet` | панель + рейка | 7..9 / 6..10 | 0..16 / 0..2 | 0..16 | `sheet` / `metal` |
| `section_planks` | доски (2 шт) | 7..9 | 2..7, 10..15 | 0..16 | `plank` (end-grain кромка на текстуре) |

Направленный «язык» — отдельные модели `section_*_tongue.json`, по 2 куба каждая:
`x 6..10` (перемычка `6..10` по ширине) и `z -4..0` по пролёту, полная высота `y 0..16`
(у `planks` — по высоте досок). Язык занимает ровно зазор между гранью секции
(`z = 0`) и гранью колонны в соседней ячейке (`z = -4`), потому что ядро опоры
занимает `4..12` в своей ячейке.

UV: `0..16` блока = полная ширина текстуры 16×16, спец-колонок нет.
`ambientocclusion: false` для cutout-моделей (`wire`, `mesh`).

**Методика проверки (инструментальная, не «на глаз»):**
- [`tools/audit_fence_assets.ps1`](tools/audit_fence_assets.ps1) — существование
  текстур, их размер, диапазон UV, отсутствие внешних `minecraft:` ссылок,
  соответствие имён моделей blockstate-ам. Результат: **0 замечаний**.
- [`tools/check_face_sampling.ps1`](tools/check_face_sampling.ps1) — для каждой
  грани каждой модели сэмплирует пиксели, которые реально покрывает UV-окно,
  и считает долю непрозрачных. Ловит «дыры» на плотных материалах и
  рассогласование пропорций грани и UV-окна. Плотные текстуры (`plank`,
  `post_wood`, `sheet`, `metal`) — 100 % непрозрачных; `wire`/`mesh`
  перфорированы по дизайну и помечены как `perforated`. Результат: **0 замечаний**.
- [`tools/check_fence_assembly.ps1`](tools/check_fence_assembly.ps1) — собирает
  `post_core` + секцию + язык во всех четырёх направлениях (`y = 0/90/180/270`),
  проверяет прилегание языка к грани колонны (`z = -4`) и к грани секции (`z = 0`),
  выход за пределы ячейки и дубликаты кубов. Результат: **0 замечаний**.

### Шаг 7. Текстуры 16×16

Пять PNG в `src/main/resources/assets/survivalpoints/textures/block/`:

| Файл | Альфа | Бесшовность | Особенности |
|---|---|---|---|
| `plank.png` | нет | горизонталь | тёмная end-grain кромка торца `#4A3A26` |
| `wire.png` | только 0/255 | горизонталь | колючки-крестики; торцевой кромки нет |
| `mesh.png` | только 0/255 | **обе оси** (вертикаль критична для стека) | ромбы «рабица» |
| `sheet.png` | нет | горизонталь | гофра + ржавчина, износ = как у mesh |
| `metal.png` | нет | не требуется | тёмный матовый металл, максимум `#6E6E72` |

Палитра: дерево `#C8A878` `#A88F5F` `#8A7048` `#6B5538`, кромка `#4A3A26`; металл `#6E6E72` `#4A444A` `#34303A` `#221F26`; ржавчина `#79513A` `#5C3D2A`. Полупрозрачных пикселей в `wire`/`mesh` быть не должно.

### Шаг 8. Blockstates и item-модели

- `post.json`: простые `variants` по `WATERLOGGED`, без multipart.
- `plank_section.json`, `wire_section.json`, `mesh_section.json`, `sheet_section.json`: `variants` по `AXIS` (`y=90` для `x`).
- Item-модели: `parent` = соответствующая block-модель.

### Шаг 9. Lang

В [`en_us.json`](src/main/resources/assets/survivalpoints/lang/en_us.json:1) и [`ru_ru.json`](src/main/resources/assets/survivalpoints/lang/ru_ru.json:1) добавить 5 имён блоков.

### Шаг 10. Render type

`cutout` для `wire_section` и `mesh_section`; `solid` для `post`, `plank_section`, `sheet_section`. Применить конвенцию проекта (проверить поддержку `render_type` в model JSON данным билдом Forge либо `ItemBlockRenderTypes`).

### Шаг 11. Сборка и загрузка мира

- Проект собирается без ошибок.
- Мир загружается, в логе нет `Missing model` / `Unable to load model`.
- 18 состояний присутствуют.
- Тик-логика отсутствует.

### Шаг 12. Скриншот-тест разрешений

Снимки на дистанции 8 / 16 / 32 блока. Оценить: читаемость нити и колючек, плотность ромбов сетки, диссонанс плотности относительно ванильной опоры (16×16). Зафиксировать решение по точечному повышению разрешения (например, только `wire` до 32×32).

### Шаг 13. Игровая проверка визуала

- Прямая линия из 8+ секций под разными углами: нет мерцающей линии на стыке секция-секция.
- Стык секция-опора: зазор ≤ 4 px, перекрытия геометрий нет.
- Вертикальные стеки: mesh↑sheet, sheet↑wire, plank↑mesh, wire↑wire (ровно 4 нити), секция на пустом нижнем ярусе.
- Г/Т/крест: одна общая опора, per-ярусная независимость.
- Одиночная секция после сноса обеих опор дропается предметом.

### Шаг 14. Документация

Обновить [`LEGACY_FENCES_INVENTORY.md`](.roo/plans/Fences/LEGACY_FENCES_INVENTORY.md): раздел legacy с пометкой «не использовать в новых задачах», ссылкой на спеку v1.1.7-final как единственный источник истины и перечнем того, что в новой системе отсутствует (кронштейн, `camp_wire_web`, направленные свойства опоры).

## Критерии готовности волны

- 5 новых блоков зарегистрированы, ошибок моделей в логе нет.
- **Нет z-fighting на стыке с любым плотным блоком** (ванильный камень, другая
  секция): язык рисуется только в сторону опоры, где уходит в воздух внутри
  колонны `4..12`. Проверяется визуально на стыке секции с ванильным блоком.
- Стык секция-секция смыкается по границе блока без зазора и без наложения
  (базовые панели `Z 0..16`, UV `0..16`).
- Legacy скрыт из креативной вкладки, но продолжает работать в существующих лагерях.
- Сетка не искажена (UV 1:1, плоскость на всю длину пролёта).
- Торцы — стержни 2×2×2, а не балки на всю длину блока.
- Все 5 текстур существуют физически, размер 16×16, альфа и бесшовность по требованиям.
- Проволока — 2 нити на блок, на высотах `4..6` и `12..14`.
- Опора — 8×8 px, без металла и направленных свойств.
- Скриншот-тест проведён, решение по разрешению зафиксировано.
- `LEGACY_FENCES_INVENTORY.md` создан и содержит предупреждение для других чатов.

## Вне рамок этой волны

- Каскад разрушения опор (§5.1 спеки) — отдельная волна.
- Datagen-провайдеры — при необходимости отдельной волной.
- Рецепты, лут-таблицы, миграция `CampGenerator` на новую систему, удаление legacy-файлов.

