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
- Два конца одновременно пустыми быть не могут: линия без соседей вдоль оси
  уходит в состояние «центр» (см. §3.1.2), поэтому `applyLine()` выставляет
  конец только при замкнутом противоположном конце. Иначе блок рисовал бы два
  среза сразу.
- Blockstate получил третье измерение условий: базовые ветки теперь требуют
  `wire_end_positive = false` **и** `wire_end_negative = false`, иначе
  `multipart` наложил бы полотно во всю ширину поверх срезанного.
- Итого состояний проволоки: `2 оси × 2 стойки × (2 варианта концов + центр)`
  против 4 до правки; block-id не добавлялись.
- Ржавые варианты: 4 parent-модели (`rusted_wire_0/1_end`, `_end_brace`) с
  переопределением только текстуры; blockstates `rusted_wire_0/1` получили те
  же ветки, что `wire_section`.

### 3.1.2 Центральное крепление: проволока верхом на опору

Дефект: проволоку нельзя было поставить **сверху** на опору, сетку или лист —
только сбоку. Причина не в коллизии и не в моделях, а в правиле
существования: [`SectionBlock.canSurvive()`](../../../src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java)
требует соседа **вдоль оси** на том же `Y`, поэтому проволока без
горизонтальных соседей отбрасывалась сразу при установке.

Решение — переопределение в `WireSectionBlock`:

```
canSurvive = сосед вдоль оси с любой стороны  ИЛИ  несущий блок снизу
```

Снизу считается то же, что даёт стойку: `PostBlock`, `MeshSectionBlock`,
`SheetSectionBlock`. Сетка и лист — полноразмерные панели, опора — колонна
`X/Z 4..12`, поэтому сверху есть на что опереться. Доски и другая проволока
по-прежнему не являются опорой.

Третье взаимоисключающее состояние `wire_center` (взамен пары концов):

| `wire_end_positive` | `wire_end_negative` | `wire_center` | Модель |
|---|---|---|---|
| `false` | `false` | `false` | `section_wire[_brace]` — линия замкнута |
| `true` | `false` | `false` | `section_wire_end[_brace]` — конец со стороны `+ось` |
| `false` | `true` | `false` | `section_wire_end[_brace]`, `y: 180` — конец со стороны `-ось` |
| `false` | `false` | `true` | `section_wire_center_brace` — соседей нет, стоит на опоре |

Модель [`section_wire_center_brace.json`](../../../src/main/resources/assets/survivalpoints/models/block/section_wire_center_brace.json)
рисует только центральный крест нитей (`X 4..12`, `Z 8..9`) и стойку
(`X 8..8`, `Z 8..9`, `Y 0..8`), без полотна во всю ширину — уходить в пустоту
нечему. Стойка в этом состоянии всегда присутствует, потому что состояние
существует только над несущим блоком, поэтому ветка `wire_center` в blockstate
не проверяет `wire_braced`.

Порядок ветвей в `multipart` важен: ветка центра идёт первой, базовые ветки
требуют `wire_center = false`, а ржавые получили 2 parent-модели
(`rusted_wire_0/1_center_brace`) с переопределением только текстуры.

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
  - wire — 6 на вариант (базовая, `_brace`, `_end`, `_end_brace`,
    `_center_brace`, `_tongue`); `_end*` и `_center_brace` — тонкие
    parent-надстройки на одноимённые базовые модели (см. §3.1.1, §3.1.2).
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

### 4.1 Коллизия, навигация и контакт проволоки (утверждено)

#### Утверждённые решения заказчика

| Вопрос | Решение |
|---|---|
| Область действия | **Только проволока**: `wire_section`, `rusted_wire_0`, `rusted_wire_1` (класс [`WireSectionBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/WireSectionBlock.java:62)). Доски, сетка, лист не затрагиваются |
| Коллизия | **Только стойка** при `wire_braced`: `box(7.5,0,7.5,8.5,16,8.5)`. Нить из коллизии исключена — сквозь проволоку можно пройти, и именно это включает контактную механику. Без стойки — `Shapes.empty()` |
| `getShape` (выделение, рейкаст) | **Переопределён одним параллелепипедом на весь крест**: `box(4,0,0,12,10,16)` (ось Z) / `box(0,0,4,16,10,12)` (ось X) плюс стойка при `wire_braced`. Базовый `SectionBlock.getShape` отдаёт панель `box(7,0,0,9,16,16)`, и при наведении подсвечивалась бы она. Габарит намеренно крупнее видимой геометрии — как у ванильного `SweetBerryBushBlock` (`box(1,0,1,15,16,15)`) |
| Контакт | Замедление `makeStuckInBlock(state, new Vec3(0.8, 0.75, 0.8))` + урон `1.0F` при движении, порог `0.003F` — механика `SweetBerryBushBlock.entityInside`. Только сервер (`level.isClientSide()` → выход) |
| Источник урона | **Ванильный** `level.damageSources().sweetBerryBush()`. Собственный `DamageType` не заводится |
| Иммунные сущности | `EntityType.FOX`, `EntityType.BEE` — как в ванили; урон только по `LivingEntity` |
| Навигация | `getBlockPathType(...)` → `BlockPathTypes.DAMAGE_OTHER` (Forge-хук, `IForgeBlock.java:547`) |
| `getAdjacentBlockPathType` | **Не добавляется** на первом шаге — только если после проверки N-2 моб будет срезать угол сквозь клетку с проволокой |
| Отклонение от D-08 | Коллизия секции из §1 спеки [`SurvivalPoints_Fences_v1.1.7-final.md`](.roo/plans/Fences/SurvivalPoints_Fences_v1.1.7-final.md:191) (`box(7,0,0,9,16,16)`) для проволоки заменена на составную форму. Зафиксировать при выпуске следующей версии спеки |

**Почему нить исключена из коллизии.** Если нить — препятствие, игрок упирается
в невидимую стену и внутрь блока не попадает: `entityInside` вызывается только
для блоков, пересекающихся с AABB сущности, поэтому контактная механика почти не
срабатывала бы (пункт C-10). Проходимая нить — условие того, что урон и
замедление вообще имеют смысл. Стойка остаётся полновысотной и не даёт пройти
насквозь там, где проволока лежит на опоре.

Побочный эффект: висящая в воздухе проволока без соседей вдоль оси больше не
препятствие для игрока вообще. Для ИИ она по-прежнему опасна —
`getBlockPathType` возвращает `DAMAGE_OTHER` независимо от коллизии.

#### Реализовано в [`WireSectionBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/WireSectionBlock.java:74)

| Что | Реализация |
|---|---|
| Коллизия | `getCollisionShape` → `SHAPE_BRACE` при `wire_braced`, иначе `Shapes.empty()`. Нить в коллизию не входит |
| `getShape` | Переопределён: `SHAPE_Z` `box(4,0,0,12,10,16)`, `SHAPE_X` `box(0,0,4,16,10,12)`, со стойкой — через `Shapes.or`. Высота `Y 0..10` покрывает диагонали (`Y ≈ 0.8..9.2`), а не только горизонтальную нить (`Y 4..6`). Формы собраны **в статическом инициализаторе**, не в вызове метода |
| Контакт | `entityInside`: `makeStuckInBlock(state, SLOWDOWN)` где `SLOWDOWN = new Vec3(0.8, 0.75, 0.8)` — статическая константа; урон `1.0F` по порогу `HURT_SPEED_THRESHOLD = 0.003F` смещения `xOld`/`zOld`; `level.isClientSide()` → урон пропускается |
| Урон | `level.damageSources().sweetBerryBush()` — ванильный `DamageSource` |
| Иммунитет | `EntityType.FOX`, `EntityType.BEE`; урон только по `LivingEntity` |
| Навигация | `getBlockPathType(...)` → `BlockPathTypes.DAMAGE_OTHER`, аннотирован `@Nullable` |

**Почему `DAMAGE_OTHER` действительно работает.** В Forge-патче
`WalkNodeEvaluator.getBlockPathTypeRaw` (строка 486) вызов
`blockstate.getBlockPathType(...)` идёт **первым**, до всех ванильных `is(...)`
и до `isPathfindable`. Ненулевой результат сразу возвращается — значит
`DAMAGE_OTHER` (малус `-1.0F`) перекрывает и форму, и `PathComputationType.LAND`.
`getAdjacentBlockPathType` не добавлялся: при `DAMAGE_OTHER` на самой клетке
моб и так не строит через неё путь.

Пункты N-1…N-7 и C-1…C-10 остаются как список ручных проверок в клиенте —
автоматических тестов в проекте нет.

#### Навигация

| # | Проверка | Где смотреть | Ожидание / развилка |
|---|---|---|---|
| N-1 | Блок получает `BlockPathTypes.DAMAGE_OTHER` | Forge-хук `IForgeBlock#getBlockPathType(BlockState, BlockGetter, BlockPos, Mob)` (`IForgeBlock.java:547`) | Моб считает клетку опасной и обходит. Без хука ванильный `WalkNodeEvaluator.getBlockPathTypeRaw` узнаёт только `Blocks.CACTUS`/`Blocks.SWEET_BERRY_BUSH` по `is(...)`, а наш блок получит тип от формы: `BLOCKED` при старой коллизии, `OPEN` при тонкой |
| N-2 | Соседние клетки получают `DANGER_OTHER` | `IForgeBlock#getAdjacentBlockPathType(...)` (`IForgeBlock.java:566`) — в Forge ровно так помечен `SWEET_BERRY_BUSH` | Моб не срезает угол сквозь клетку с проволокой; без хука угловой путь проходит вплотную |
| N-3 | `isPathfindable(..., PathComputationType.LAND)` | [`SectionBlock.getCollisionShape()`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:216) → `WalkNodeEvaluator` (`:534`) | Развилка: `false` — мобы обходят, но «тонкая» коллизия перестаёт быть тонкой для ИИ; `true` — проходимо, навигация проволоку не видит вообще. Решение фиксируется в спеке |
| N-4 | Уточнение имени типа | 1.20.1 `net.minecraft.world.level.pathfinder.BlockPathTypes` | **`PathType` в 1.20.1 не существует** (появился в 1.21). Единственный путь — `BlockPathTypes` + два Forge-хука выше; искать «PathType-хук» в этой версии бесполезно |
| N-5 | Малус пути отрицателен | `Mob#getPathfindingMalus(BlockPathTypes)` | `FENCE`/`DAMAGE_OTHER` дают `-1.0F` (`BlockPathTypes`), узел отвергается: путь не строится через проволоку, моб ищет обход |
| N-6 | `FENCE`-семантика | `WalkNodeEvaluator` (`:530`): `BlockTags.FENCES` / `BlockTags.WALLS` → `FENCE` | Решить, добавлять ли блок в `minecraft:fences`-тег. Тег даёт обход без Java-кода, но меняет поведение соединений у ванильных блоков и влияет на `isPathfindable`-логику соседей |
| N-7 | Мобы мода | `BaseSurvivorEntity`, `BaseGuardianEntity` ([`ModEntityRenderers.java`](src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:28)) | Проверить, что NPC не застревают у линии и не пытаются пройти сквозь неё; при `DAMAGE_OTHER` — не считают её проходимой |

#### Контакт: урон и замедление (по аналогии со сладкими ягодами)

| # | Проверка | Где смотреть | Ожидание / развилка |
|---|---|---|---|
| C-1 | Замедление через `makeStuckInBlock` | `SweetBerryBushBlock.entityInside` | `entity.makeStuckInBlock(state, new Vec3(0.8F, 0.75D, 0.8F))` — тот же множитель, что у ягод. Проверить, что эффект не суммируется с водой и с несколькими блоками проволоки в одной клетке |
| C-2 | Урон только при движении | `HURT_SPEED_THRESHOLD = 0.003F`, сравнение `xOld`/`zOld` в `SweetBerryBushBlock.entityInside` | Стояние в блоке не наносит урон; урон идёт при фактическом смещении. Проверить частоту: 1.0F за каждый тик движения — снять замер, при необходимости добавить кулдаун |
| C-3 | Источник урона | `level.damageSources().sweetBerryBush()` | Развилка: переиспользовать ванильный `DamageSource` (0 datapack-работы) либо завести `survivalpoints:barbed_wire` (`DamageType` + datapack-JSON + `DamageSources`-хелпер). Второй вариант даёт свой текст смерти и корректную статистику |
| C-4 | Исключения сущностей | ваниль: `instanceof LivingEntity && type != FOX && type != BEE` | Решить список иммунных: только игрок? игрок + NPC мода? мобы-животные? Проверить, что урон не убивает питомцев и не ломает `Mob#setTarget` |
| C-5 | Сторона вычисления | `if (!level.isClientSide && ...)` в ванили | Урон и звук — только на сервере; клиент не должен дублировать эффект |
| C-6 | Взаимодействие с `WATERLOGGED` | [`SectionBlock.getFluidState()`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:222) | Проверить поведение в затопленном состоянии: урон/замедление сохраняются или отключаются; отдельно — плавание у поверхности проволоки |
| C-7 | Отсутствие побочных эффектов | `neighborChanged` / `destroyBlock` в [`SectionBlock.java`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:202) | Урон не должен вызывать блок-апдейты, разрушение проволоки, рекурсию `updateShape` и спам частиц/звука |
| C-8 | Производительность | `entityInside` вызывается каждый тик на каждую сущность в блоке | Внутри — ноль аллокаций и никаких `Shapes.or`; `Vec3` для `makeStuckInBlock` держать статической константой, а не создавать в вызове |
| C-9 | Звук и фидбек | `SoundEvents` при уроне | Решить, нужен ли звук «зацепился за проволоку»; если да — только `SoundSource.BLOCKS` и с ограничением частоты |
| C-10 | Коллизия + урон вместе | [`SectionBlock.getCollisionShape()`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:216) | Проверить оба сценария: старая полная коллизия (замедление почти не срабатывает — игрок упирается в стену) и тонкая (игрок проходит сквозь, урон и замедление работают). От этого зависит, есть ли смысл в C-1..C-3 вообще |

## 5. Не сделано / на будущее

- Визуальная проверка блоков в мире проводилась заказчиком (подтверждено).
  Отображение item-слоя в GUI после правок §3.5 в клиенте ещё не проверялось.
- Рецепты и loot-таблицы для 6 новых блоков не создавались: по `D-15` рецепты —
  заглушки, а лут для секций в проекте пока не сгенерирован ни для одного блока
  (в `data/survivalpoints/loot_tables/blocks` есть только `camp_gate` и
  `camp_radio`). Дроп с себя у новых блоков появится вместе с остальными секциями.
- Лимит «5 block-id» из §1 спеки фактически превышен — при выпуске следующей
  версии спеки это нужно зафиксировать.
- Коллизия проволоки заменена по решению §4.1 (вариант A) — это отклонение от
  D-08 в §1 спеки, требует записи при выпуске следующей версии.
- `getAdjacentBlockPathType` и запись в `minecraft:fences`-тег не добавлялись:
  по решению §4.1 они вводятся только при подтверждённой необходимости (пункты
  N-2 и N-6).