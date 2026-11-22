.# План: устранение z-fighting на стыках секций (tip = 0 + направленные языки к опоре)

## 1. Проблема

На скриншоте заказчика стыки **слева** (секция ↔ опора) выглядят корректно,
а стыки **справа** показывают «другие текстуры», в том числе ванильные.

### 1.1. Корневая причина

Текущая реализация использует санкционированное отступление `tip = 4`:
все секции имеют геометрию по оси пролёта `Z -4..20`, то есть выступают
на 4 px за границу своего блока в **обе** стороны.

| Сосед | Что происходит в зоне выступа `Z 0..4` | Результат |
|---|---|---|
| [`PostBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/PostBlock.java:46) — колонна `4..12` | У опоры зоны `0..4` и `12..16` по X и Z — **воздух**. Выступ секции висит в пустоте, ни с одной гранью не совпадает | Текстура чистая. Это «слева нормально» |
| Плотный ванильный блок (камень, лава-блок и т.п.) | Блок занимает зону `0..4` целиком. Грань выступа секции и грань ванильного блока попадают **в одну плоскость** | Z-fighting: видеокарта мерцает между двумя текстурами. Видна «чужая» ванильная текстура |
| Другая секция | Её выступ идёт навстречу. Две панели лежат в плоскости `X=7..9` с разными материалами | Z-fighting между двумя нашими текстурами |
| Воздух | Выступ висит в пустоте | Визуально приемлемо, но геометрия выходит за блок |

Дополнительный усилитель: UV длинных граней натянут на 24 px
(например [`section_planks.json`](src/main/resources/assets/survivalpoints/models/block/section_planks.json:13) —
`[1,0,17,5]`). В полосе перекрытия сосед показывает **другой участок** текстуры,
поэтому дефект читается не как мерцание, а как «другая текстура».

### 1.2. Почему сдвиг на 0.01 px не подходит

Разведение граней по X на 0.01 px убирает мерцание только для одного типа
соседа, но вносит субпиксельный шум в 16-px текстуру и не решает случай,
когда сосед — другая секция того же материала.

## 2. Решение

Выступ перестаёт быть константой модели и становится **функцией от соседа**:
язык (tongue) рисуется только в ту сторону, где стоит [`PostBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/PostBlock.java:41),
и не рисуется к плотным блокам и к другим секциям.

Свойства z-fighting не возникает **по построению**, потому что:

- базовая панель снова `Z 0..16` — стык секция-секция смыкается строго
  по границе блока: ни зазора, ни наложения;
- язык `Z -4..0` уходит в воздух внутри колонны опоры, где нет ни одной
  грани, с которой он мог бы совпасть.

### 2.1. Что остаётся от прежнего решения

- Зазор у опоры остаётся закрытым (язык перекрывает 4 px до поверхности колонны).
- Коллизия (D-08) **не меняется**: `box(7,0,0,9,16,16)` / `box(0,0,7,16,16,9)`.
  Языки чисто визуальные.
- `canSurvive` сохраняет прежнюю семантику: вдоль оси хотя бы один
  `PostBlock` или `SectionBlock`.

### 2.2. Что отменяется

Шапка [`fences-v1.1.7-implementation-plan.md`](.roo/plans/_archive/fences-v1.1.7-implementation-plan.md:3)
с «санкционированным отступлением tip = 4» **переписывается**: отступление
отменено как источник дефекта, возвращается `tip = 0` из спеки плюс
направленные языки.

## 3. Изменения в Java

### 3.1. [`SectionBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:43)

Добавить четыре булевых свойства:

```java
public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
public static final BooleanProperty EAST  = BlockStateProperties.EAST;
public static final BooleanProperty WEST  = BlockStateProperties.WEST;
```

Зарегистрировать все четыре в `createBlockStateDefinition` рядом с `AXIS` и `WATERLOGGED`.

**Почему ванильные `BlockStateProperties.NORTH/SOUTH/EAST/WEST`, а не свои:**
это стандартные `BooleanProperty` из `BlockStateProperties`, они уже
используются в blockstate-JSON как `north=true` и не требуют
дополнительной регистрации.

**Количество состояний:** `2 (AXIS) × 2 (WATERLOGGED) × 2⁴ = 64` на блок-id,
`256` суммарно на 4 block-id. Для реестра это нормально.

### 3.2. `getStateForPlacement`

После выбора оси выставить стороны, в которых стоит опора:

```java
BlockState state = this.defaultBlockState().setValue(AXIS, axis).setValue(WATERLOGGED, ...);
return state
    .setValue(NORTH, isPostAt(level, pos, Direction.NORTH))
    .setValue(SOUTH, isPostAt(level, pos, Direction.SOUTH))
    .setValue(EAST,  isPostAt(level, pos, Direction.EAST))
    .setValue(WEST,  isPostAt(level, pos, Direction.WEST));
```

где `isPostAt` — `level.getBlockState(pos.relative(dir)).getBlock() instanceof PostBlock`.

### 3.3. `updateShape`

Пересчитывать свойства при изменении соседа. Это единственное место,
где состояние синхронизируется с реальным окружением:

```java
@Override
public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                              LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
    if (state.getValue(WATERLOGGED)) {
        level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
    }
    BooleanProperty side = sideProperty(direction);
    if (side != null) {
        boolean post = neighborState.getBlock() instanceof PostBlock;
        if (state.getValue(side) != post) {
            return state.setValue(side, post);
        }
    }
    return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
}
```

`sideProperty(Direction)` возвращает `NORTH/SOUTH/EAST/WEST` для горизонтальных
направлений и `null` для `UP`/`DOWN`.

**Важно:** `super.updateShape` вызывается всегда, чтобы не сломать
waterlog-логику. Возврат `state.setValue(...)` вместо `super` допустим
только когда состояние действительно изменилось — иначе лишние
обновления блока каждый `neighborChanged`.

### 3.4. `canSurvive` — без изменений

Текущая реализация через `connectsAlong(level, pos, state.getValue(AXIS))`
остаётся. Языки на выживание не влияют: секция может стоять между двумя
секциями без опор.

## 4. Изменения в моделях

### 4.1. Базовые панели — вернуть к `0..16`

| Файл | Было (tip = 4) | Стало (tip = 0) |
|---|---|---|
| [`section_mesh.json`](src/main/resources/assets/survivalpoints/models/block/section_mesh.json:9) | `[7,0,-4] .. [9,16,20]`, UV `[0,0,24,16]` | `[7,0,0] .. [9,16,16]`, UV `[0,0,16,16]` |
| [`section_sheet.json`](src/main/resources/assets/survivalpoints/models/block/section_sheet.json:10) | панель `-4..20`, рейка `-4..20` | панель `0..16`, рейка `0..16` |
| [`section_wire.json`](src/main/resources/assets/survivalpoints/models/block/section_wire.json:9) | нити `-4..20` | нити `0..16` |
| [`section_planks.json`](src/main/resources/assets/survivalpoints/models/block/section_planks.json:9) | доски `-4..20`, UV `[1,0,17,5]` | доски `0..16`, UV `[1,0,17,5]` |

UV длинных граней: длина 16 px вместо 24 px. Для `plank` сохраняется
1 px отступа с каждой стороны (кромка end-grain), то есть `[1,0,17,5]`.

### 4.2. Новые модели-языки (4 файла)

Файлы в `src/main/resources/assets/survivalpoints/models/block/`:

| Файл | Геометрия | UV торца |
|---|---|---|
| `section_planks_tongue.json` | доски `X 7..9`, `Y 2..7` и `10..15`, `Z -4..0` | `[0,0,2,5]` на `north` |
| `section_mesh_tongue.json` | панель `X 7..9`, `Y 0..16`, `Z -4..0` | `[0,0,2,16]` на `north` |
| `section_sheet_tongue.json` | панель `X 7..9`, `Y 0..16`, `Z -4..0` + рейка `X 6..10`, `Y 0..2`, `Z -4..0` | `[0,0,2,16]` на `north` |
| `section_wire_tongue.json` | нити `X 7..9`, `Y 4..6` и `12..14`, `Z -4..0` | `[0,0,2,2]` на `north` |

**Грани в языках:** рисовать только `north` (торец) и длинные боковые
`east`/`west` на длину 4 px. Грани `south` не нужна — она упирается
в базовую панель, `cullface` не ставить нигде (язык висит в воздухе,
соседние грани не должны его отсекать).

**UV длинных граней языка:** `[0,0,4,N]`, где `N` — высота элемента.
Это продолжение текстуры в правильном масштабе 1:1.

**`ambientocclusion: false`** — как и у базовых моделей.

### 4.3. Item-модели — без изменений

`parent` указывает на базовую панель; языки в инвентаре не нужны,
потому что предмет не имеет состояния.

## 5. Изменения в blockstates

Четыре файла переводятся с `variants` на `multipart`:

- [`plank_section.json`](src/main/resources/assets/survivalpoints/blockstates/plank_section.json:1)
- [`wire_section.json`](src/main/resources/assets/survivalpoints/blockstates/wire_section.json:1)
- [`mesh_section.json`](src/main/resources/assets/survivalpoints/blockstates/mesh_section.json:1)
- [`sheet_section.json`](src/main/resources/assets/survivalpoints/blockstates/sheet_section.json:1)

Структура (пример для `plank_section`):

```json
{
  "multipart": [
    { "when": { "axis": "z" }, "apply": { "model": "survivalpoints:block/section_planks" } },
    { "when": { "axis": "x" }, "apply": { "model": "survivalpoints:block/section_planks", "y": 90 } },

    { "when": { "axis": "z", "north": "true" }, "apply": { "model": "survivalpoints:block/section_planks_tongue" } },
    { "when": { "axis": "z", "south": "true" }, "apply": { "model": "survivalpoints:block/section_planks_tongue", "y": 180 } },

    { "when": { "axis": "x", "east":  "true" }, "apply": { "model": "survivalpoints:block/section_planks_tongue", "y": 90 } },
    { "when": { "axis": "x", "west":  "true" }, "apply": { "model": "survivalpoints:block/section_planks_tongue", "y": 270 } }
  ]
}
```

**Соответствие направлений и поворотов** — критичный момент, проверено в игре:

Модель языка нарисована в `Z -4..0`, то есть направлена на **NORTH** (`-Z`).
В blockstate `y: 90` — это `Rotation.CLOCKWISE_90`, при котором NORTH
переходит в EAST. Отсюда:

| Свойство | Ось панели | Поворот языка | Куда смотрит язык |
|---|---|---|---|
| `north=true` | `axis=z` | `y=0` | NORTH (`-Z`) — как нарисовано |
| `south=true` | `axis=z` | `y=180` | SOUTH (`+Z`) |
| `east=true` | `axis=x` | `y=90` | EAST (`+X`) |
| `west=true` | `axis=x` | `y=270` | WEST (`-X`) |

> ⚠️ **Исправленный дефект.** В первой реализации `east` и `west` были
> перепутаны (`y=270` для east, `y=90` для west). Симптом: при `axis=x`
> язык уходил в противоположную сторону — у столба оставался зазор 4 px,
> а язык торчал внутри соседней секции. При `axis=z` всё работало, потому
> что `north`/`south` были заданы верно.

`waterlogged` в `when` **не указывается**: панель и языки одинаковы для
обоих значений, поэтому условия по нему избыточны.

## 6. Порядок работ

| # | Шаг | Файлы |
|---|---|---|
| 1 | Переписать шапку плана: отменить `tip = 4`, зафиксировать причину | [`fences-v1.1.7-implementation-plan.md`](.roo/plans/_archive/fences-v1.1.7-implementation-plan.md:3) |
| 2 | Добавить 4 свойства и регистрацию | [`SectionBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:43) |
| 3 | `getStateForPlacement` — выставить стороны по опорам | [`SectionBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:72) |
| 4 | `updateShape` — пересчёт сторон | [`SectionBlock`](src/main/java/com/phian404/survivalpoints/camp/block/fence/SectionBlock.java:191) |
| 5 | Вернуть базовые панели к `0..16` и UV `0..16` | 4 модели `section_*` |
| 6 | Создать 4 модели-языка | 4 новых `*_tongue.json` |
| 7 | Перевести blockstates на `multipart` | 4 blockstate-файла |
| 8 | Сборка `gradlew.bat build -x test` | — |
| 9 | Проверка в игре | — |
| 10 | Дополнить критерии готовности | [`fences-v1.1.7-implementation-plan.md`](.roo/plans/_archive/fences-v1.1.7-implementation-plan.md:207) |

## 7. Проверки приёмки

### 7.1. Обязательные (блокируют волну)

- Сборка `BUILD SUCCESSFUL`, в логе нет `Missing model` / `Unable to load model`.
- Стык **секция-опора**: зазор закрыт, язык виден как продолжение панели.
- Стык **секция-секция**: на границе блоков нет ни зазора, ни полосы, ни мерцания.
- Секция рядом с **ванильным плотным блоком**: нет z-fighting, ванильная текстура не просвечивает.
- Поворот `axis=x`: языки стоят с той стороны, где реально опора (проверка таблицы из §5).

### 7.2. Регрессионные

- Линия из 8+ секций под разными углами — нет мерцающей линии на стыках.
- Г/Т/крест на одной опоре — языки рисуются во все занятые стороны.
- Вертикальные стеки материалов (mesh↑sheet, sheet↑wire, plank↑mesh) — без разрывов.
- Секция на пустом нижнем ярусе не падает (D-18).
- Одиночная секция после сноса обеих опор дропается предметом.
- Waterlog: секция под водой сохраняет `waterlogged=true` и не теряет языки.

### 7.3. Что считать провалом

Если после правок на стыке с ванильным блоком всё ещё видно мерцание —
значит язык рисуется не в ту сторону (ошибка в таблице поворотов §5)
или `cullface` где-то включён. В этом случае не менять геометрию,
а проверять `when`-условия в blockstate.

## 8. Вне рамок

- Каскад разрушения опор (§5.1 спеки) — отдельная волна.
- Datagen-провайдеры — отдельной волной.
- Рецепты, лут-таблицы, миграция `CampGenerator`, удаление legacy.
- Замена текстур — на заказчике (R-05).

## 9. Правило U1 — UV торцевых граней (волна «торцы 2 px»)

Все четыре материала приведены к единому поперечному сечению **2 px**
(`X 7..9`). Торцевые грани `north`/`south` используют правило **U1**:

- `u 0..2` — ровно 2 текселя, что соответствует толщине 2 px (масштаб 1:1);
- `v` — **тот же диапазон**, что у длинной грани `east`/`west` этого элемента.

Следствие: рисунок на торце непрерывен с боковиной, шва на угле нет.

**Исключение — `plank`:** торец `u 1..3` вместо `0..2`. Колонки `0..1`
в `plank.png` — тёмная end-grain кромка; сдвиг на 1 делает торец светлее
и убирает излишний контраст с боковиной.

| Файл | Элемент | Торец `north`/`south` |
|---|---|---|
| `section_mesh.json` | панель `Y 0..16` | `[0,0,2,16]` |
| `section_sheet.json` | полотно `Y 2..16` | `[0,0,2,14]` |
| `section_wire.json` | нить `Y 8..14` | `[0,6,2,10]` |
| `section_wire.json` | нить `Y 3..9` | `[0,10,2,16]` |
| `section_planks.json` | доска `Y 10..15` | `[1,0,3,5]` |
| `section_planks.json` | доска `Y 2..7` | `[1,0,3,5]` |

**Полотно листа:** `X 8..8.5` (0.5 px) → `X 7..9` (2 px). Рейка остаётся
`X 6..10` — выступ 1 px с каждой стороны. После расширения полотно языка
`section_sheet_tongue.json` (`X 7..9`) совпадает с панелью, ступенька исчезает.

**UV боковин листа:** `[0,0,16,14]` → `[0,0,16,16]` — высота полотна 14 px,
прежний UV растягивал текстуру на 14 из 16 доступных.

**No-op `rotation`** (`angle: 0`) удалён из `section_sheet.json`,
`section_wire.json` (×2), `section_planks.json`.

**Bedrock-поля** `format_version` и `credit` удалены из `section_sheet.json`.

### 9.1. Статус реализации

| Проверка | Результат |
|---|---|
| Валидация 8 моделей | OK: badUV=0, nullUV=0, degenerate=0, badTexRef=0, noop-rotation=0 |
| Blockstates | 4 файла × 6 частей, все ссылки на модели существуют |
| `gradlew.bat build -x test` | BUILD SUCCESSFUL in 12s |
| Хеши `src` ↔ `build` | все 8 моделей MATCH |
| Игровая проверка | не проводилась |