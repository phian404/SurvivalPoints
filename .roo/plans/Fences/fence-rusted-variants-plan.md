# Ржавые вариации секций ограждений — план и результат

Статус: **ВЫПОЛНЕНО** (Forge 1.20.1, `com.phian404.survivalpoints`).

Задача заказчика: «добавить в креатив ещё по две вариации сетки и листа металла,
то есть их ржавые версии, но при этом оставить прежнюю логику и геометрию, то есть
те же модельки, но с разными текстурами».

## 1. Решение заказчика

- Форма реализации: **отдельные block-id и предметы** (`rusted_mesh_0/1`,
  `rusted_sheet_0/1`), логика та же.
- Материалы в объёме задачи: `wire`, `sheet` и `mesh` (индекс `0` — слабый налёт
  ржавчины, `1` — сильный). Текстуры сетки заказчик доделывает сам.

## 2. Отклонение от канона

[`SurvivalPoints_Fences_v1.1.7-final.md`](SurvivalPoints_Fences_v1.1.7-final.md) §1
фиксирует лимит «5 block-id; 18 состояний». Новые 6 block-id этот лимит превышают.
Отклонение согласовано заказчиком в этой задаче; остальные требования спеки
(геометрия, коллизия, waterlogged, canSurvive, отсутствие BlockEntity/тиков)
не менялись.

## 3. Что реализовано

### 3.1 Логика сшивания (единственная правка поведения)

[`StackedSectionBlock`](../../../src/main/java/com/phian404/survivalpoints/camp/block/fence/StackedSectionBlock.java)
раньше сшивал секции строго по идентичности блока (`is(this)`). Введён хук
`isSameMaterial(Block)`; [`MeshSectionBlock`](../../../src/main/java/com/phian404/survivalpoints/camp/block/fence/MeshSectionBlock.java)
и [`SheetSectionBlock`](../../../src/main/java/com/phian404/survivalpoints/camp/block/fence/SheetSectionBlock.java)
переопределяют его на `instanceof` своего класса.

Итог: базовый и ржавые варианты одного материала сшиваются по вертикали как один
материал (рейка на торце убирается, панель продлевается до границы блока), а
разные материалы по-прежнему не сшиваются.

Проволока не требует правок по материалам: [`WireSectionBlock`](../../../src/main/java/com/phian404/survivalpoints/camp/block/fence/WireSectionBlock.java)
проверяет соседа снизу через `instanceof MeshSectionBlock / SheetSectionBlock`,
поэтому ржавая проволока над ржавой сеткой тоже получает стойку.

Отдельной правкой правило стойки расширено: `isPanelBelow()` заменён на
`isSupportBelow()` — стойка `section_wire_brace` рисуется, если снизу стоит
несущий элемент: сплошная панель **или опора** (`PostBlock`). Колонна опоры
занимает `X/Z 4..12` полной высоты, вертикальная стойка проволоки — `X 8..8,
Z 8..9`, `Y 0..8` по центру, поэтому она продолжает опору без зазора и сдвига.
Доски и другая проволока стойку по-прежнему не дают.

`SectionBlock.isConnectable()` использует `instanceof SectionBlock` — новые блоки
автоматически участвуют в горизонтальных соединениях и `canSurvive`. Метод
переведён из `private` в `protected static`: семейство проволоки считает по
нему незамкнутые концы линии, и правило соседа обязано совпадать с условием
существования блока, иначе конец линии мог бы разойтись с `canSurvive`.

### 3.1.1 Незамкнутые концы линии проволоки

Заказчик: «проволока — последняя в линии, и с одной стороны нет соседа»;
полотно нельзя оставлять обрезанным «в пустоту».

Состояния (2 новых булевых свойства у `WireSectionBlock`, по одному на конец
оси; имена по знаку координаты, а не «право/лево» — право и лево зависят от
точки зрения игрока):

| Свойство | Значение `true` | Модель (ось `z`) | Ось `x` |
|---|---|---|---|
| `wire_end_positive` | нет соседа со стороны `+ось` (`SOUTH` / `EAST`) | `section_wire_end[_brace]` | `y: 270` |
| `wire_end_negative` | нет соседа со стороны `-ось` (`NORTH` / `WEST`) | `section_wire_end[_brace]`, `y: 180` | `y: 90` |

- Модель-конец одна на обе стороны: геометрия обрезана до центра (`Z 0..9`)
  и вторая сторона получает её поворотом на 180°. Отдельный файл `_left` не
  создаётся — это был бы полный дубликат геометрии.
- `section_wire_end` — полотно без стойки; `section_wire_end_brace` — с ней.
  Стойка независима от конца и по-прежнему определяется `isSupportBelow()`,
  поэтому конец линии над опорой/панелью сохраняет стойку.
- Два конца одновременно пустыми быть не могут: `canSurvive` требует хотя бы
  одного соседа вдоль оси, поэтому `applyEnds()` выставляет конец только при
  замкнутом противоположном конце. Иначе блок рисовал бы два среза сразу.
- Blockstate получил третье измерение условий: базовые ветки теперь требуют
  `wire_end_positive = false` **и** `wire_end_negative = false`, иначе
  `multipart` наложил бы полотно во всю ширину поверх срезанного.
- Итого состояний проволоки: `2 оси × 2 стойки × (3 варианта концов) = 24`
  против 4 до правки; block-id не добавлялись.
- Ржавые варианты: 4 parent-модели (`rusted_wire_0/1_end`, `_end_brace`) с
  переопределением только текстуры; blockstates `rusted_wire_0/1` получили те
  же ветки, что `wire_section`.

### 3.2 Регистрация

[`ModBlocks`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java):

| Block-ID | Класс | Предмет |
|---|---|---|
| `rusted_mesh_0` | `MeshSectionBlock` | `RUSTED_MESH_0_ITEM` |
| `rusted_mesh_1` | `MeshSectionBlock` | `RUSTED_MESH_1_ITEM` |
| `rusted_sheet_0` | `SheetSectionBlock` | `RUSTED_SHEET_0_ITEM` |
| `rusted_sheet_1` | `SheetSectionBlock` | `RUSTED_SHEET_1_ITEM` |
| `rusted_wire_0` | `WireSectionBlock` | `RUSTED_WIRE_0_ITEM` |
| `rusted_wire_1` | `WireSectionBlock` | `RUSTED_WIRE_1_ITEM` |

### 3.3 Клиент и вкладка

- [`ModEntityRenderers`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java):
  `cutout` для всех 6 новых блоков (в текстурах есть прозрачные пиксели).
- [`ModCreativeTabs`](../../../src/main/java/com/phian404/survivalpoints/init/ModCreativeTabs.java):
  6 предметов добавлены после базовых секций.
- `lang`: 6 имён в `en_us.json` и `ru_ru.json`
  (`… (Light)` / `… (Heavy)` и `(ржавая, слабая)` / `(ржавая, сильная)`).

### 3.4 Ассеты

Геометрия не дублируется: каждая модель-вариация — тонкая надстройка с `parent`
на базовую модель и переопределением только переменной текстуры.

- blockstates (6): `rusted_mesh_0/1`, `rusted_sheet_0/1`, `rusted_wire_0/1.json` —
  полная копия условий `mesh_section` / `sheet_section` / `wire_section`
  (axis, `*_above/below`, `wire_braced`, `wire_end_*`, языки к опоре).
- block-модели (34):
  - mesh — 8 на вариант (`_bottom`, `_top`, `_middle`, `_tongue`,
    `_tongue_bottom`, `_tongue_top`, `_tongue_middle` + базовая);
  - sheet — 8 на вариант (тот же набор);
  - wire — 5 на вариант (базовая, `_brace`, `_end`, `_end_brace`, `_tongue`);
    `_end*` — тонкие parent-надстройки на `section_wire_end` /
    `section_wire_end_brace` (см. §3.1.1).
- item-модели (6): `parent` на `section_<материал>_inventory` + override текстур
  (см. §3.5).
- текстуры: `rust.png`-набор заказчика — `rusted_mesh_0/1.png`,
  `rusted_sheet_0/1.png`, `rusted_wire_0/1.png` в
  [`textures/block/`](../../../src/main/resources/assets/survivalpoints/textures/block).
  Структура `textures` моделей ссылается на них, поэтому доделанные заказчиком
  PNG подхватываются без правок JSON.

### 3.5 Отображение предметов в инвентаре (item-слой)

Отдельная задача по просьбе заказчика: «более корректное отображение в инвентаре»,
**без изменения геометрии блоков в мире**.

Причина дефекта: block-модели секций не имеют `parent`, поэтому
[`BlockModel.getTransform()`](../../../src/main/java/net/minecraft/client/renderer/block/model/BlockModel.java)
возвращал `NO_TRANSFORM` для всех контекстов — предмет рисовался тонкой плоскостью
без объёма и без ракурса.

Решение: ванильный `display`-ракурс `fence_inventory` (`gui [30, 135, 0] / 0.625`,
`fixed [0, 90, 0] / 0.5`) + собственные `elements` без опор.

- Новые модели `models/block/section_{mesh,sheet,wire,planks}_inventory.json`
  (4 файла): `parent: minecraft:block/block`, собственные `elements` — только
  полотно материала и его металлические планки, без деревянных столбов
  (по указанию заказчика: опоры в item-отображении не нужны, это отдельный блок
  `post`). `ground`/`thirdperson`/`firstperson` наследуются от `block/block`,
  т.к. дочерняя модель переопределяет только `gui` и `fixed`.
- Состав `elements` по материалам: mesh — `mesh` + `core_up` + `core_down`;
  sheet — `sheet` + `core_up` + `core_down`; wire — `wire_a` + `wire_b` +
  `wire_core`; planks — `plank_low` + `plank_high`.
- `models/item/*.json` (11 файлов): `parent` переключён на `*_inventory`;
  у 6 ржавых добавлен override `textures` (`mesh`/`sheet`/`wire` → ржавый PNG),
  у `post` — только `display` (свой `parent: block/post_core` сохранён).
- `blockstates/*` и `models/block/section_*.json` **не изменялись** ⇒ вид в мире
  идентичен подтверждённому ранее.

Причина, по которой геометрия продублирована в одном файле, а не собрана цепочкой
`parent`-ов: `BlockModel.getElements()` возвращает либо свои `elements`, либо
элементы родителя — слияния нет, поэтому добавить к секции планки/полотно из разных
моделей через `parent` невозможно.

## 4. Проверки

| Проверка | Результат |
|---|---|
| `gradlew.bat compileJava --rerun-tasks` | **BUILD SUCCESSFUL** (15 warnings `[removal]`, все предсуществующие) |
| JSON новых ассетов (50 файлов) | валидны, `PROBLEMS: 0` |
| Разрешимость всех `parent`-ссылок | все найдены, битых нет |
| JSON item-слоя (11 item + 4 `*_inventory`) | валидны, `PROBLEMS: 0`; все `parent` и текстуры разрешаются |
| Наследование `display` | `getTransform()` берёт `gui`/`fixed` из `*_inventory`, остальные контексты — из `block/block` |

Офлайн-валидаторы `tools/*.ps1` в этой задаче не запускались: они не влияют на
сборку и работу игры, а новые модели наследуют уже проверенную геометрию базовых.

## 5. Не сделано / на будущее

- Визуальная проверка блоков в мире проводилась заказчиком (подтверждено).
  Отображение item-слоя в GUI после правок §3.5 в клиенте ещё не проверялось.
- Рецепты и loot-таблицы для 6 новых блоков не создавались: по `D-15` рецепты —
  заглушки, а лут для секций в проекте пока не сгенерирован ни для одного блока
  (в `data/survivalpoints/loot_tables/blocks` есть только `camp_gate` и
  `camp_radio`). Дроп с себя у новых блоков появится вместе с остальными секциями.
- Лимит «5 block-id» из §1 спеки фактически превышен — при выпуске следующей
  версии спеки это нужно зафиксировать.