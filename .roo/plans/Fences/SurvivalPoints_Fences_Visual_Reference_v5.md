# SurvivalPoints Fences — Visual Reference v5

Рабочий визуальный ориентир для текстур/моделей. Не переписывает техническую спецификацию: геометрия секций, коллизии, соединения, число нитей и принцип вертикального комбинирования взяты из спеки v1.1.7-final без изменений.

## Обновление v5 относительно v4

Опора сужена с 10×10 px (`3..13`) до 8×8 px (`4..12`), по аналогии с пропорциями ванильной стены Minecraft. При `tip = 0` зазор у опоры увеличен с 3 до 4 px. Геометрия секций, текстуры, палитра, вертикальное комбинирование и прочие визуальные решения v4 сохранены.

## 0. Статус решений

Принято (визуальное и техническое упрощение, без новых механик):

- Полный отказ от муфтов, хомутов, крепёжных пластин и болтов на опорах. Опора = обычное обтёсанное бревно с ванильными текстурами `minecraft:block/stripped_oak_log` и `minecraft:block/stripped_oak_log_top`; своих текстур опоры нет.

- Секции визуально примыкают непосредственно к опоре; дополнительных металлических креплений нет.

- **`tip = 0` (закрытие §P):** выступов за границу блока нет; концы панелей/нитей/досок заполняют свой блок строго `0..16` по оси и примыкают впритык к соседям и к столбу. Это убирает латентный Z-fight на стыках секция-секция (существовавший при `tip = 1`) и зазор-костыль у столба; остаточный зазор `4 px` между концом секции и поверхностью колонны фиксируется как допустимое ограничение v1 (невидимо на игровой дистанции).

- Проволока = строго 2 нити на секцию (D-02).

- Единый характер износа металлических поверхностей (mesh и sheet — одинаковая степень потёртости/ржавчины).

- Гвозди на досках убраны; тёмная торцевая кромка (`end-grain`) сохранена **только** для `plank` (физиологична на любом стыке). Затемнение торца **НЕ** применяется к `wire`/`mesh`/`sheet` (см. §4: конфликт с секция-секция у cutout).

- Рейка листа = металл (`metal`), не дерево.

- Добавлены примеры вертикального комбинирования разных материалов.

Отменено по ходу досчёта (не применяется):

- Трюк «затемнённая торцевая колонка для всех секций» — отменён, ломает стык секция-секция у cutout и не даёт направленной маскировки без запрещённых свойств.

Открыто (решение за заказчиком, НЕ применено молча):

- Разрешение своих текстур (`plank`, `wire`, `mesh`, `sheet`) — открытый параметр (16 / 32 / spec), финал = скриншот-тест в игре; 32 не утверждён.

## 1. Палитра v5 (без «хрома»)

Ни один металлический пиксель не светлее `#6E6E72`. Износ (смешение металла с ржавчиной) одинаков на mesh и sheet.

| Группа | Hex (свет→тьма) | Назначение v4 |

|---|---|---|

| Дерево (plank, волокно) | `#C8A878` `#A88F5F` `#8A7048` `#6B5538` | база `#A88F5F`; светлое/тёмное волокно |

| Дерево (кромка/торец) | `#4A3A26` | тёмная торцевая кромка **только** досок (`end-grain`) |

| Металл (рейка листа) | `#6E6E72` `#4A444A` `#34303A` `#221F26` | база `#4A444A`; **только** рейка листа, на опорах металла нет |

| Ржавчина (mesh, sheet) | `#79513A` `#5C3D2A` | единая для mesh и sheet |

Опора: ванильные `stripped_oak_log` / `stripped_oak_log_top` (16×16, 1:1 с мировым пикселем), своих пикселей не вводится.

## 2. Текстуры v5: состав, разрешение, бесшовность

Своих текстур = 5 (`plank`, `wire`, `mesh`, `sheet`, `metal`); опора = 2 ванильные ссылки (0 своих). `metal` используется **только** для рейки листа. Бюджет файлов не меняется; `tip = 0` не добавляет и не убирает текстур.

UV при `tip = 0` тривиальна: `16 px` блока = полная ширина текстуры (тайлинг на блок), спец-колонок «+tip/−tip» **нет** (они были нужны только для tips). Бесшовность по горизонтали сохраняется как требование для стыка секция-секция (торцы впритык, текстура стыкуется непрерывно).

Разрешение — открытый параметр. Физический факт: мировая толщина линии = текстурная толщина ÷ (N/16), поэтому высокое разрешение не бесплатное — требует жирнее рисовать линии, иначе на дистанции они схлопнутся в <1 мировой px; жирные линии вблизи убивают тонкость. Читаемость нити на дистанции ограничена её мировой толщиной 2 px (фиксировано спекой) и разрешением не лечится.

| Файл | spec (64) | 32 | 16 | Комментарий |

|---|---|---|---|---|

| `plank` | 64×16 | 32×8 | 16×4 | доска в мире 16×5×2 px; 16×4 = 1:1 с опорой; тёмная `end-grain` кромка торца = 1px колонка, симметрична, допустима на любом стыке; зазор/высота фиксированы геометрией |

| `wire` | 64×8 | 32×4 | 16×2 | нить мировая 2 px; 16×2 → колючки негде рисовать; 32/64 → колючки видны вблизи; на 32+ блоках колючки пропадают при любом N (mipmap), сама нить 2 px мерцает на дистанции при любом N — ограничение геометрии; торцевая кромка **не** вводится |

| `mesh` | 64×64 | 32×32 | 16×16 | 16×16 → ромб ~4 мировой px = ванильная chain-плотность, лучшая дистанция и 1:1 с опорой; 64 → линии толще, иначе пропадут; торцевая кромка **не** вводится |

| `sheet` | 64×64 | 32×32 | 16×16 | сплошной, без альфы → не мерцает при любом N; высокое разрешение безопасно (ржавчина/потёртости); торцевая кромка **не** вводится |

| `metal` | 16×16 | 16×16 | 16×16 | рейка листа мелкая, 16 достаточно; не масштабируем |

Системный конфликт плотности: опора = 16 (ваниль); свои = 64 → в кадре дерево 1:1 и металл 1:4. Лечение: (а) все свои 16; (б) все свои 32 + перерисовать опору в 32; (в) смешанно; (г) принять диссонанс и проверить скриншотами. Гипотеза (не решение): `wire`/`mesh`/`plank` = 32, `sheet` = 64, `metal` = 16, опора = оставить 16 и проверить на скриншоте. Финал только после снимков на 8/16/32/64 блоков.

Требования бесшовности v4:

| Файл | Горизонталь (вдоль оси) | Вертикаль (стык ярусов) | Альфа |

|---|---|---|---|

| `plank` | бесшовно | не требуется (доски дискретны) | нет |

| `wire` | бесшовно | не требуется (нити дискретны) | только 0/255 |

| `mesh` | бесшовно | **бесшовно (критично для стека, D-17)** | только 0/255 |

| `sheet` | бесшовно | не требуется (рейка = пояс на границе) | нет |

| `metal` | не требуется | не требуется | нет |

Приёмка: мощение 2×2 без швов; в `wire`/`mesh` нет полупрозрачных пикселей (иначе cutout даёт дыры/мерцание); торцевая кромка только у `plank`.

## 3. Геометрия секций v5 (`tip = 0`, без изменений прочего)

Таблица элементов спеки 6.2 сохранена дословно по координатам и высотам (нити 4..6 / 12..14, доски 2..7 / 10..15 с зазором 3 px, толщина 2 px), **за вычетом выступов**: строка «Муфта» удалена (муфтов нет), из всех строк секций убрано `+ tips −1..0, 16..17` — элементы идут строго `0..16` по оси. Рейка листа → `metal`. Гвоздей нет; `plank` имеет тёмную `end-grain` кромку торца (текстура, не геометрия).

| Элемент | X | Y | Z | Текстура v4 |

|---|---|---|---|---|

| Нити (2) | 7..9 | 4..6 и 12..14 | 0..16 | `wire` |

| Панель mesh | 7..9 | 0..16 | 0..16 | `mesh` |

| Панель sheet | 7..9 | 0..16 | 0..16 | `sheet` |

| Рейка sheet | 6..10 | 0..2 | 0..16 | `metal` |

| Доски (2) | 7..9 | 2..7 и 10..15 | 0..16 | `plank` (+`end-grain` кромка торца) |

UV: `0..16` блока = полная ширина текстуры; спец-колонок нет. **Примечание:** при `tip = 0` на стыке секция-опора остаётся `4 px` воздуха между концом панели (граница блока) и поверхностью колонны (`4..12`) — допустимое ограничение v1, см. §4.

## 4. Стык секция-опора: РЕШЕНО `tip = 0` (закрытие §P)

История и обоснование принятого решения (для понимания Coding AI, не для пересмотра):

- `tip = 1` (P-A в v3): оставлял латентный Z-fight секция-секция (боковые грани tips копланарны-перекрывающиеся) **плюс** зазор у столба — отклонено.

- `tip = 3` (P-B): убирал зазор у столба, но усиливал Z-fight секция-секция до 3 px по боковым граням — отклонено (хуже P-A).

- `tip ≥ 4` (P-C): врез в непрозрачную колонну → Z-fight секция-опора без маскировки — отклонено.

- асимметричные tips (P-D): требовали направленных свойств у `SectionBlock` — отклонено (нарушение запрета на новые механики/свойства).

- «пролёт не доходит до столба» как стилистика (P-E): нарушало формулировку «концы скрыты» — отклонено.

- **`tip = 0` (принято):** торцы панелей стыкуются впритык; противоположные нормали и разнесение боковых граней `X = 7,9` в разные `Z`-диапазоны делают стык секция-секция ожидаемо чистым; фактическое отсутствие мерцания подтверждается проверкой на CP4, а не принимается как гарантия рендерера. У столба конец панели на границе блока, колонна от `4 px` → нет перекрытия геометрий, Z-fight секция-опора исключён. Цена: `4 px` воздуха у столба, невидимый на игровой дистанции; вблизи читается как «панель примыкает впритык с малым отступом» (норма для модульного забора со редкими столбами). Маскировка затемнением торца **не** вводится для cutout (конфликт с секция-секция, см. §0); `end-grain` кромка `plank` остаётся как физиологичный торец бруска.

- Формулировка «концы скрываются внутри объёма опоры» (п.1 отказа от муфтов) переосмыслена как «концы примыкают впритык к поверхности столба без креплений»: буквальное «внутри объёма» совместимо с отсутствием геометрического перекрытия только через P-D (запрещён) или возврат маскировки (против отказа); `tip = 0` жертвует именно этой буквой как наименьшей ценой. Отсутствие мерцания на стыке секция-секция отдельно проверяется эмпирически на CP4.

## 5. Вертикальное комбинирование: примеры для референса

Принцип D-03/D-17/D-18 не менялся (`tip` не влияет на вертикальные стыки, они по `Y`). На стыке ярусов: полновысотные панели (mesh/sheet) сходятся ребро-в-ребро в дорожке X 7..9 → непрерывная плоскость, шов маскируется бесшовной текстурой mesh; дискретные элементы (нити, доски) границу не пересекают → ритм продолжается; рейка `metal` листа = стыковочный пояс на каждой блочной границе. Обязательные примеры:

1. `mesh` (низ) ↑ `sheet` (верх) — непрерывность плоскости + пояс рейки на границе.

2. `sheet` (низ) ↑ `wire` (верх) — сплошной лист + 2 нити выше, рейка на границе.

3. `plank` (низ) ↑ `mesh` (верх) — доски + сетка, зазор 3 px продолжается ритмом.

4. `wire` (низ) ↑ `wire` (верх) — ровно 4 нити на 0.25/0.75/1.25/1.75 (контроль D-02).

5. Ярус с пустым низом (`sheet` на Y+1 без секции на Y) — допустимо D-18.

6. Г/Т/крест-пересечение на двух ярусах — одна общая опора (чистое бревно, без креплений); per-ярусная независимость соединений секций через `canSurvive` (D-06/D-18); муфтов нет, направленных свойств опоры нет.

## 6. Послойные промпты для генерации превью v4

### COMMON HEADER (вставлять в начало КАЖДОГО из пяти запросов)

```text

Vanilla Minecraft Java Edition 1.20 pixel-art voxel style. Dark neutral background. Isometric view. Flat lighting, soft ambient occlusion. Crisp nearest-neighbor pixels. Slightly desaturated natural palette. NO shaders, NO raytracing, NO PBR, NO smooth shading, NO realism, NO text, NO labels, NO watermarks.

```

### PANEL A — изолированные компоненты

```text

[COMMON HEADER]

Draw six separate objects in a row on a dark background. Each object is exactly one 1x1x1 block. Nothing protrudes past the block boundary.

Object 1: a plain hewn square-log post, about 8/16 (half) of block width (8×8 px footprint; coordinates 4..12 on X/Z), vanilla stripped-oak texture with vertical grain and concentric growth-ring top face. The post stands ALONE with no base, no pedestal, no grass block underneath, no metal fasteners of any kind. It is just a bare log column.

Object 2: two such posts stacked vertically, showing the honest seam at the block boundary between them. Still no base, still no fasteners.

Object 3: a wire section containing EXACTLY TWO horizontal barbed-wire strands. One strand sits at quarter-height, the other at three-quarter-height. Both are thin 3D cylinders with tiny X-shaped barbs. They end flush at the block faces. There are NOT three strands, NOT one strand — exactly two.

Object 4: a mesh section = a FLAT vertical diamond chain-link panel, rusty gray-brown, see-through holes, thickness 2 pixels. NOT a 3D box, NOT a cage, NOT a volumetric frame — a flat plane with transparency. Ends flush at the block boundary.

Object 5: a sheet section = a FLAT vertical corrugated metal panel with rust patches, AND a thin DARK-METAL bottom rail spanning the full width at the very bottom of the block. The bottom rail IS present and visible. Metal color is dark matte, brightest pixel #6E6E72, NO white or chrome highlights. Ends flush.

Object 6: a plank section = EXACTLY TWO horizontal wooden boards with a visible gap between them. NOT three boards, NOT one board — two. Each board has a dark end-grain edge (#4A3A26) on its short ends. No nails, no screws, no metal brackets. Ends flush.

CRITICAL REMINDERS FOR THIS IMAGE:

- Two wire strands only, never three, never one.

- Mesh is FLAT, not a 3D box.

- Sheet HAS a dark-metal bottom rail.

- Plank is TWO boards, not three.

- Posts have NO base, NO grass block, NO metal anything.

- Nothing protrudes past any block boundary.

```

### PANEL B — стыки секция-опора и секция-секция

```text

[COMMON HEADER]

Draw two close-up joint scenarios side by side on a dark background.

Scenario LEFT: a plain hewn stripped-oak post (bare log, no fasteners, no base) standing vertically. To its east, one wire section (two strands) approaches and STOPS exactly at the wire section's own block boundary. To its west, one mesh section (flat panel) approaches and STOPS exactly at its own block boundary. Between each section's end and the log surface there is a small ~4-pixel air recess. Render this honestly: it is a flush butt-joint with a tiny visible recess. DO NOT draw the section penetrating into the log. DO NOT add any bracket, clamp, plate, bolt, or muff to hide the recess. At normal viewing distance the recess would be invisible; here we show it up close so it is truthful.

Scenario RIGHT: two adjacent same-type sections along the run (both wire, or both mesh). Their flat ends meet edge-to-edge at the shared block boundary with ZERO overlap and ZERO protruding tongue. The seam between them is hidden purely by the seamless horizontal texture pattern and by opposite face normals. There is NO flickering line, NO double-thickness band, NO overlapping geometry down the middle of the run.

CRITICAL REMINDERS FOR THIS IMAGE:

- Section ends STOP at their own block boundary, they do NOT enter the post.

- The 4-pixel recess at the post is HONEST, not masked by invented hardware.

- Adjacent sections meet EDGE TO EDGE with zero overlap.

- No metal fasteners anywhere in this image.

- Posts remain bare logs with no base.

```

### PANEL C — вертикальные стеки разных материалов

```text

[COMMON HEADER]

Draw five vertical stacking examples in a row on a dark background. Each example shows two 1x1x1 blocks stacked on top of each other (total height 2 blocks). All elements stay within their own block boundaries — nothing protrudes.

Stack 1: chain-link mesh (flat panel) on the BOTTOM block + corrugated sheet (flat panel WITH dark-metal bottom rail) on the TOP block. The two flat panels meet edge-to-edge at the Y=16 boundary with no gap and no overlap. The sheet's dark-metal bottom rail reads as a clear seam band right at that boundary.

Stack 2: corrugated sheet on the BOTTOM block + wire section (two strands) on the TOP block. The sheet fills its entire block height; the wire strands sit at quarter and three-quarter height of the upper block. Clean transition at Y=16.

Stack 3: plank section (two boards with gap) on the BOTTOM block + chain-link mesh on the TOP block. The 3-pixel gap between the two boards continues naturally across the boundary — the rhythm of wood-and-gap does not break at Y=16.

Stack 4: two wire sections stacked = EXACTLY FOUR strands total, positioned at heights 0.25 / 0.75 / 1.25 / 1.75 of the combined 2-block structure. Verify: count four distinct horizontal barbed-wire lines, evenly spaced. NOT three, NOT five.

Stack 5: corrugated sheet floating on the UPPER tier with an EMPTY lower tier (air below it). This demonstrates that sections are independent and can exist without support beneath them. Show the sheet cleanly suspended, no falling debris, no broken connection.

In ALL five stacks, if a post appears at the end of the run, it is a bare log with no fasteners and no base.

CRITICAL REMINDERS FOR THIS IMAGE:

- Stack 4 has exactly FOUR wire strands (two per block × two blocks).

- Mesh and sheet are FLAT panels, not boxes.

- Sheet always has its dark-metal bottom rail.

- Plank always has exactly TWO boards.

- Empty-lower-tier stack is valid and intentional.

- No protrusions past any block boundary.

```

### PANEL D — соединения (Г/Т/крест) на траве

```text

[COMMON HEADER]

Draw five junction layouts on green grass terrain, viewed from above at a slight isometric angle. Orthogonal geometry only — no diagonals, no curves.

Junction 1 (straight): post — wire section — post — wire section — post. A simple linear run. Each post is a bare log, no fasteners, no base decoration.

Junction 2 (90-degree corner): ONE shared post at the corner point, with a wire section extending north from it and a mesh section extending east from it. Only ONE post exists at the corner cell. No duplicate posts.

Junction 3 (T-junction): ONE shared post at the T intersection, with wire sections extending south, east, and west from it. Three connections, one post.

Junction 4 (cross): ONE shared post at the center, with sections extending all four cardinal directions. Four connections, one post.

Junction 5 (end post): a single post with only ONE section attached (say, east), the other three sides empty. Shows what a terminal post looks like.

Additionally, show a two-tier version of Junction 3: the lower post carries only the lower-tier sections, and the upper post (stacked directly above) carries only the upper-tier sections. Per-tier independence comes from where the SECTIONS are placed, NOT from any bracket or connector drawn onto the post itself. The post remains visually identical regardless of how many tiers of sections attach to it at different heights.

In ALL junctions: every section end stops flush at its own block boundary; the ~4px recess near the post is acceptable and expected; no metal fasteners appear on any post; posts stand directly on grass with no artificial pedestal.

CRITICAL REMINDERS FOR THIS IMAGE:

- Corners/T/crosses use ONE shared post, never multiple overlapping posts.

- Posts are bare logs everywhere, no fasteners, no pedestals.

- Sections stop flush at block boundaries.

- Per-tier independence is shown WITHOUT drawing connectors on the post.

- Orthogonal only, no diagonals.

```

### PANEL E — текстурные свотчи + палитра

```text

[COMMON HEADER]

Draw five flat 2D pixel-art texture swatches arranged in a row on a dark background, then two color palette rows below them. Crisp pixels, no anti-aliasing, no gradients beyond pixel steps.

Swatch 1 (plank): a horizontal wood strip showing grain direction left-to-right. The SHORT ENDS of the strip carry a darker end-grain tone (#4A3A26), clearly distinguishable from the lighter body (#A88F5F range). No nails, no screw heads, no metal marks. Seamless horizontally.

Swatch 2 (wire): a horizontal barbed-wire motif — a thin cylinder with periodic tiny X-shaped barb clusters. Background around the wire is fully TRANSPARENT (alpha = 0 outside the wire shape, alpha = 255 inside). No semi-transparent pixels. No darkened end columns. Seamless horizontally.

Swatch 3 (mesh): a diamond chain-link lattice pattern. Lines are rusty gray-brown. Background between diamonds is fully TRANSPARENT (alpha strictly 0 or 255, nothing in between). Seamless on BOTH axes (horizontal AND vertical). No darkened end columns.

Swatch 4 (sheet): a vertical corrugated metal panel with subtle rust patches. Opaque (no alpha). Wear level matches the mesh swatch — similar rust density, not cleaner, not dirtier. Seamless horizontally. No darkened end columns.

Swatch 5 (metal): a small dark matte gray swatch representing ONLY the bottom-rail material of the sheet section. Brightest pixel is #6E6E72. Absolutely NO white highlight, NO chrome reflection, NO glossy sheen. Not used on posts.

Below the swatches, two palette rows of colored squares:

Row 1 (wood): #C8A878 → #A88F5F → #8A7048 → #6B5538 → #4A3A26 (five squares, light to dark).

Row 2 (metal/rust): #6E6E72 → #4A444A → #34303A → #221F26 → #79513A → #5C3D2A (six squares, grays then rust tones).

CRITICAL REMINDERS FOR THIS IMAGE:

- Wire and mesh have STRICT binary alpha (0 or 255 only).

- Mesh is seamless on BOTH axes.

- Metal swatch max brightness is #6E6E72, no brighter.

- End-grain darkening appears ONLY on plank, nowhere else.

- Sheet wear level equals mesh wear level.

```

## 7. Чек-лист валидации превью v5 (сверять до финальных PNG)

1. Проволока: ровно 2 нити на блок; 4 нити на двухблочном заборе (0.25/0.75/1.25/1.75). Генератор дал 3 — брак, не повод менять D-02.

2. Опора: чистое бревно, **никаких** металлических креплений/хомутов/пластин/болтов; секции примыкают напрямую.

3. `tip = 0`: никаких геометрийных выступов за границу блока ни на стыке секция-опора, ни на стыке секция-секция; на превью не должно быть «язычков», торчащих в столб или в соседнюю секцию.

4. Стык секция-секция: на CP4 проверить линию из 8+ секций под разными углами камеры; критерий — отсутствие мерцающей линии, подтверждённое наблюдением. Если мерцание проявилось на конкретном билде — правка UV/толщины на уровне ассета (±1 px), без смены геометрии и без возврата tips/муфтов.

5. Стык секция-опора: `4 px` воздуха у столба присутствует и **принимается** как ограничение v1 (невидим на дистанции); не маскировать выдуманной муфтой или затемнением cutout-торца.

6. Рейка листа: тёмный металл, не дерево; доски без гвоздей, с `end-grain` кромкой торца **только** у `plank`; у `wire`/`mesh`/`sheet` торцевая кромка отсутствует.

7. Износ: mesh и sheet одинаковой степени ржавчины/потёртости; mesh не «чистый пластик».

8. Палитра металла: нет белого/хрома/глянца; максимум света `#6E6E72`; металл только на рейке листа.

9. Вертикальные стеки: панели непрерывны на границе ярусов (нет щели/наложения), рейка = пояс, нити/доски дискретны без артефактов, пустой нижний ярус висит корректно; бесшовность mesh по вертикали видна на стеках (скрин 3a); при сомнении — мощение 2×2 в редакторе.

10. Density: на скринах 1/4/5 оценить, не режет ли глаз разница плотности опоры (16) и своих текстур; это вход в выбор разрешения (§2), не автоматический провал.

11. Только ортогональ; один общий столб на Г/Т/крест; per-ярусная независимость соединений секций видна без каких-либо креплений на опоре.

Важно: превью = мудборд для текстур и настроения. Финальный источник геометрии — Blockbench по таблице §3 (спека 6.2 минус строка муфты минус tips, плюс рейка→metal, плюс `end-grain` кромка `plank`); генераторы не гарантируют тайлинг, высоты нитей, `tip = 0` и отсутствие Z-fight, поэтому числа из §3/§2/§4 приоритетнее любой картинки.

## 8. Соответствие спеке v1.1.7-final

Настоящий референс не правит спеку и полностью ей соответствует v1.1.7-final: 5 block-id; 18 состояний; 5 model JSON (post_core без муфты, 4 секции); 5 своих текстур + 2 ванильные ссылки; metal только рейка листа; tip = 0 (D-10/6.2; стык секция-секция проверяется эмпирически на CP4); коллизии D-08 без изменений; каскад §5.1 с оговоркой отложенного статуса; §P закрыт. Открытым остаётся только разрешение своих текстур (§2), согласованное со спекой §6.3.1 как открытый параметр.
