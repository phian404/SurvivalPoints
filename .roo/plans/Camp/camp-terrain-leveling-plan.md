# План: выравнивание площадки лагеря по медиане рельефа

Задача: разрешить размещение лагеря на неровной поверхности с перепадом до 2 блоков,
с подготовкой участка (подсыпка/срез/расчистка) и сохранением текущих конвенций генерации.

Затрагиваемые файлы (только эти):

- [`CampTuning.java`](../../../src/main/java/com/phian404/survivalpoints/camp/CampTuning.java)
- [`CampSitePlanner.java`](../../../src/main/java/com/phian404/survivalpoints/camp/CampSitePlanner.java)
- [`CampGenerator.java`](../../../src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java)
- [`CampManager.java`](../../../src/main/java/com/phian404/survivalpoints/camp/CampManager.java)

## Согласованные решения

1. Скан колонки начинается от `Heightmap.Types.MOTION_BLOCKING_NO_LEAVES` и идёт вниз.
2. Порядок проверок внутри скана: `!state.getFluidState().isEmpty()` (вода/лава → отказ) →
   пропуск `BlockTags.LOGS`/`BlockTags.LEAVES` → пропуск `state.canBeReplaced()`.
   Порядок важен: вода и лава сами являются `canBeReplaced()`.
3. `groundBlockY` — первый блок, прошедший фильтры. `surfaceAirY = groundBlockY + 1`.
4. Поверхностный `BlockState` каждой клетки сохраняется в плане, чтобы вернуть
   `grass_block`/`sand` и т. п. на уровень `floorY - 1` вместо голого `dirt`.
5. Перепад = `max(surfaceAirY) - min(surfaceAirY)`, порог `CAMP_MAX_HEIGHT_DELTA = 2`.
6. `floorY = median(surfaceAirY)`; при чётном количестве клеток — `Math.round` среднего
   двух центральных значений. В генератор передаётся как есть.
7. Колонка `anchor` исключена из всех операций изменения блоков. Блок-якорь и его
   координаты в `CampRecord` не меняются.
8. `prepareTerrain()` вызывается ровно один раз — из `CampManager.createCamp()`, до
   `addCamp`. В `generate()` повторного вызова нет.
9. Проверка `BlockEntity` — периметр + `CAMP_CLEAR_MARGIN` наружу, по Y от `minY - 1`
   до `floorY + CAMP_CLEAR_HEADROOM`; сам `anchor` из проверки исключён.
10. Срез грунта не выходит за пределы площадки. Подсыпка края и подхода к воротам —
    только в наружной полосе `CAMP_CLEAR_MARGIN`.

## Сохраняемые конвенции `floorY`

| Элемент | Уровень |
|---|---|
| Грунт двора | `floorY - 1` |
| Периметр (опоры, панели, проволока) | от `floorY` |
| Вышка (`watchtower`) | `floorY` |
| Ферма (`farm`) | `floorY - 1` |
| `setPath()` | меняет блок на `floorY - 1` |
| `fillFarmBase()` | остаётся без изменений |

Смещения модулей, повороты, логика заборов, ворот и размещения NBT не унифицируются
и не меняются.

## Шаги

### 1. `CampTuning`

- Заменить `CAMP_MAX_HEIGHT_STEP` на `CAMP_MAX_HEIGHT_DELTA = 2`.
- Добавить `CAMP_CLEAR_MARGIN = 3` — ширина наружной полосы проверки и подсыпки края.
- Добавить `CAMP_CLEAR_HEADROOM = 8` — верхняя граница расчистки над `floorY`.
- Обновить javadoc: обосновать 2 блока и назначение новой полосы.

### 2. `CampSitePlanner.Plan`

- Добавить `int[] surfaceAirY` и `BlockState[] surfaceStates` — сетка `0..side`,
  индекс `x * side + z`.
- Добавить геттеры `cellSurfaceY(int lx, int lz)` и `cellSurfaceState(int lx, int lz)`.
- Прокинуть оба массива через приватный конструктор `Plan`.

### 3. `CampSitePlanner.plan()` — определение поверхности

- Убрать проход по `Heightmap` с отдельной проверкой жидкости.
- Для каждой клетки площадки сканировать сверху вниз от
  `MOTION_BLOCKING_NO_LEAVES` до `level.getMinBuildHeight()`:
  - жидкость (`water`/`lava` в `FluidState`) → немедленный отказ с координатами;
  - `BlockTags.LOGS` / `BlockTags.LEAVES` → пропуск;
  - `state.canBeReplaced()` → пропуск;
  - иначе — это `groundBlockY`, выход из цикла.
- Заполнить `surfaceAirY[cell] = groundBlockY + 1`, `surfaceStates[cell]` — состояние
  блока на `groundBlockY`.
- Собрать `min`/`max` по `surfaceAirY`.

### 4. `CampSitePlanner.plan()` — перепад и `floorY`

- Проверка перепада: `max - min > CampTuning.CAMP_MAX_HEIGHT_DELTA` → отказ с числами.
- `floorY` = медиана массива `surfaceAirY`:
  - сортировка копии массива;
  - нечётное количество — центральный элемент;
  - чётное — `Math.round((a + b) / 2.0)`;
  - никаких `+1`/`-1` к результату.

### 5. `CampSitePlanner.plan()` — позиции модулей

Оставить существующую локальную геометрию и повороты:

- `gatePos`, `watchtowerPos`, `radioPos` — `floorY`;
- `farmPos` — `floorY - 1`;
- `watchtowerRotation` / `farmRotation` — без изменений.

### 6. `CampSitePlanner` — проверка зоны подготовки

Новый метод (возвращает причину отказа или `null`):

- границы: периметр ± `CAMP_CLEAR_MARGIN` по X и Z;
- Y: от `minY - 1` до `floorY + CAMP_CLEAR_HEADROOM`;
- обход через `level.getBlockEntity(pos)`, при непустом результате — отказ
  «Найден объект (BlockEntity) у x,y,z»;
- колонка `anchor` пропускается — иначе `CampRadioBlockEntity` установленной
  радиовышки блокировал бы создание лагеря.

### 7. `CampManager.createCamp()` — порядок

```
checkModules → plan → проверка зоны (BlockEntity)
  → CampGenerator.prepareTerrain(level, plan, anchor)
  → new CampRecord + addCamp
  → generate → createPostsFromPois → setDirty
```

- Любой отказ до `addCamp` — мир и `CampSavedData` не изменены.
- `prepareTerrain` вызывается только здесь, в `generate()` его нет.

### 8. `CampGenerator.prepareTerrain(level, plan, anchor)`

Единственная точка подготовки участка. Проход по клеткам площадки
`0..side × 0..side`, для каждой клетки — последовательно:

1. **Расчистка.** От `groundBlockY + 1` до `floorY + CAMP_CLEAR_HEADROOM`: удалять
   `LOGS`, `LEAVES` и `canBeReplaced()` (`AIR`).
2. **Подсыпка.** Если `groundBlockY + 1 <= floorY - 2`: заполнить `dirt` от
   `groundBlockY + 1` до `floorY - 2`.
3. **Поверхность.** На `floorY - 1` поставить сохранённый `surfaceStates[cell]`
   (для низких клеток — после подсыпки, для высоких — после среза). Это возвращает
   `grass_block`/`sand` вместо голого `dirt`.
4. **Срез.** Если `groundBlockY >= floorY`: удалить блоки от `floorY` до `groundBlockY`.
   Только внутри периметра.

Колонка `anchor` (`x == anchor.getX() && z == anchor.getZ()`) исключена из всех
четырёх операций.

### 9. `CampGenerator.prepareTerrain()` — край и подход к воротам

- Локальная подсыпка края: клетки в полосе `CAMP_CLEAR_MARGIN` снаружи периметра, где
  поверхность ниже `floorY`, поднимаются до `floorY` (не выше), чтобы не оставлять
  обрыв в 1–2 блока.
- Подход к воротам: от `plan.getGatePos().south()` вниз к уровню окружающей поверхности,
  шаг не более 1 блока на клетку; подсыпка только там, где нужен подъём.
- Срез в наружной полосе не выполняется.

### 10. Совместимость

Проверить, что не изменены:

- `generate()` — вызов `prepareTerrain` отсутствует, порядок периметра/модулей/путей/радио
  прежний;
- `fillFarmBase()` — без изменений;
- `setPath()` — по-прежнему `floorY - 1`;
- смещения модулей: вышка `floorY`, ферма `floorY - 1`;
- `isWatchtowerCell`, `isGateSpan`, `isPostCell`, `placeGateLeaf`, `placeWire`, `placeSheet`.

### 11. Проверка

- `gradlew.bat compileJava` — зафиксировать результат.
- Итог: перечислить изменённые файлы, что сделано, результат компиляции.
- Не утверждать о проверке в игре, если игра не запускалась.

## Вне области задачи

- Случайная генерация лагерей в мире.
- Спиральный/мировой поиск площадок.
- Универсальный терраформинг и подпорные стены.
- Универсальная защита построек игрока (белый список натуральных блоков).
- Перенос блока-якоря и правки `CampRadioBlock`/`CampRecord`/`onRemove()`.
