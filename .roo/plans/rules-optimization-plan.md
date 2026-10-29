# План: Волна A + Волна C — оптимизация правил, режимов и структуры конфигурации

> **Статус:** финализирован, ожидает реализации.
> **Проект:** SurvivalPoints (Minecraft Forge 1.20.1, Java 17).
> **Источник:** первичный аудит системы правил, промптов, режимов и инструментов Zoo Code (форк Roo Code).
> **Связанные документы:** [`.roo/plans/README.md`](README.md) (навигатор), [`Entities/plan.md`](Entities/plan.md) (канон архитектуры NPC), [`AI_CONTEXT.md`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md).

---

## 0. Уже выполнено (шаги 1–3, не входят в этот план)

Правки внесены в [`.roomodes`](../../.roomodes) и проверены парсером `js-yaml`:

1. [`.roomodes:36`](../../.roomodes) — режим `code`: несуществующий `single_find_and_replace` заменён на `search_replace`. Подтверждение: в бандле расширения `zoocodeorganization.zoo-code-3.92.0` нет ни одного вхождения `single_find_and_replace`, тогда как `search_replace` объявлен классом инструмента.
2. [`.roomodes:40`](../../.roomodes) — режим `architect`: снят запрет «НЕ меняешь файлы», который противоречил пункту 5 инструкций; формулировка приведена в соответствие с группой правок `edit` + `fileRegex: \.md$`.
3. [`.roomodes:47`](../../.roomodes) — режим `architect`: фиксированный `.roo/plans/plan.md` заменён на уникальное имя `.roo/plans/<тема>-plan.md`.
4. [`.roomodes:9`](../../.roomodes) — режим `survivalpoints-audit`: добавлено `source: project` для однородности.

Также выполнено владельцем вне плана: удалены `.roo/backups/` и `.roo/plans/image.png`.

---

## 1. Цель плана

Устранить подтверждённые аудитом дублирования, противоречия и мёртвые артефакты в конфигурации Zoo Code, не меняя игровую логику мода. Java-код не затрагивается — кроме одной правки ссылки в [`AI_CONTEXT.md`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md).

---

## 2. Волна A — правила и навигатор

Только текстовые изменения. Перезагрузка расширения не требуется; правила подхватываются в следующих сессиях.

### A1. Перенос правила `update_todo_list` в общий набор

**Файл:** [`.roo/rules/anti-loop.md`](../../.roo/rules/anti-loop.md)

**Проблема.** Правило об аргументе `update_todo_list` лежит в [`.roo/rules-code/codestyle.md:8-14`](../../.roo/rules-code/codestyle.md), то есть в каталоге, привязанном к режиму `code`. Но инструмент используется и в `architect` ([`.roomodes:45`](../../.roomodes)), и в `survivalpoints-audit`. Режимы, кроме `code`, правило не получают.

**Изменение.** Добавить в конец [`.roo/rules/anti-loop.md`](../../.roo/rules/anti-loop.md) новый раздел:

```markdown
## Tool Call Rule: update_todo_list

- When calling the `update_todo_list` tool, provide exactly ONE argument named `todos`.
- The value must be a single Markdown-formatted string: "- [ ] First task\n- [-] Second task\n- [x] Third task".
- CRITICAL: DO NOT use a `tasks` array. DO NOT output a JSON array. Use only the `todos` string.
```

**Ожидаемый результат.** Правило действует во всех трёх режимах; устраняется источник повторявшихся неудачных вызовов инструмента.

### A2. Удаление дубля правила из режимного каталога

**Файл:** [`.roo/rules-code/codestyle.md`](../../.roo/rules-code/codestyle.md)

**Проблема.** Блок «Tool Call Rule: update_todo_list» (строки 8–14) — правило инструмента внутри файла стиля Java-кода. Смешение категорий; после A1 становится дублем.

**Изменение.** Удалить строки 8–14. Файл заканчивается на строке 7 (`- Keep existing comments and @Override annotations intact.`).

**Ожидаемый результат.** `codestyle.md` снова содержит только стилевые стандарты.

### A3. Реструктуризация `minecraft.md`: Стиль / Версия / Верификация

**Файл:** [`.roo/rules/minecraft.md`](../../.roo/rules/minecraft.md)

**Проблема.** Раздел «## Strict API Rules» (строки 17–22) смешивает три несвязанные категории: пункт 1 — стилевое требование (`Be concise. No apologies.`), пункт 3 — ограничение версии, пункты 2 и 4 — политика верификации. Один заголовок не отражает содержимое.

**Изменение.** Заменить раздел на три отдельных раздела:

```markdown
## Response Style

1. Be concise. No apologies.

## API Version Lock

1. **Version Lock:** Use only APIs compatible with Minecraft Forge 1.20.1. Never use methods or classes introduced in Minecraft 1.21+.

## Verification Policy

1. **Vanilla Minecraft API.** Before implementing or recommending an unfamiliar vanilla API, verify it using the `minecraft-modding` MCP server (`inspect-minecraft` or `analyze-symbol`) when available.
2. **Forge API.** The `minecraft-modding` MCP server does not index Forge classes. Verify Forge signatures against the Forge sources in the Gradle cache instead.
3. Never guess API signatures. If neither source can be checked, state the limitation explicitly instead of pretending verification succeeded.
```

**Ожидаемый результат.** Прозрачная структура; каждый тип ограничения правится точечно.

### A4. Перевод верификации Forge на Gradle-кэш

**Файл:** [`.roo/rules/minecraft.md`](../../.roo/rules/minecraft.md)

**Проблема.** Текущий [`.roo/rules/minecraft.md:20`](../../.roo/rules/minecraft.md) требует проверять любой из API через MCP, но MCP не индексирует Forge-классы. Факт зафиксирован в проекте (в удалённом этапном плане `plan.md`, строки 35 и 170): «**Не подтверждено MCP:** Forge-классы (`EntityRenderersEvent.RegisterLayerDefinitions`, `registerLayerDefinition(...)`) не индексируются — возвращают `not_found`» и «MCP Forge-классы не индексирует. Без подтверждения шаг 1 не выполнять».

**Изменение.** Входит в A3: пункт «Forge API» предписывает сверку по исходникам в Gradle-кэше.

**Ожидаемый результат.** Правило становится выполнимым; убирается причина «застревания» на шаге проверки API.

### A5. Ссылка на навигатор в правилах

**Файл:** [`.roo/rules/minecraft.md`](../../.roo/rules/minecraft.md)

**Проблема.** Блок «## Context» (строки 5–13) перечисляет документы, но не указывает точку входа — навигатор [`.roo/plans/README.md`](README.md), который объявлен таковой в §«Как выбрать документ».

**Изменение.** Добавить в блок Context строку:

```markdown
- Documentation entry point: `.roo/plans/README.md` (choose the one relevant document per task; do not read all files).
```

**Ожидаемый результат.** Единый маршрут чтения планов; меньше лишних `list_files`/`search_files`.

### A6. Снятие дубля нормы «группировать правки и проверки»

**Файлы:** [`.roo/rules/anti-loop.md:13`](../../.roo/rules/anti-loop.md) и [`.roo/rules-code/git.md:9`](../../.roo/rules-code/git.md)

**Проблема.** Одна норма продублирована дословно в двух файлах с разной областью загрузки:

- [`.roo/rules/anti-loop.md:13`](../../.roo/rules/anti-loop.md) — «Group related file edits, tool calls, and verification into one workflow.»
- [`.roo/rules-code/git.md:9`](../../.roo/rules-code/git.md) — «Do not repeat analysis or start a new workflow after each file edit or Git operation. Group related edits and verification into a single workflow.»

**Изменение.** Норму оставить в [`.roo/rules/anti-loop.md:13`](../../.roo/rules/anti-loop.md). В [`.roo/rules-code/git.md:9`](../../.roo/rules-code/git.md) заменить строку на ссылку:

```markdown
- Grouping of edits and verification: see `.roo/rules/anti-loop.md` (General Rules).
```

**Ожидаемый результат.** Единая точка правки нормы; при изменении не возникнет расхождения файлов.

### A7. Удаление пустого общего файла правил

**Файл:** [`.roo/rules/rules.md`](../../.roo/rules/rules.md)

**Проблема.** Файл пуст (одна пустая строка). Создаёт ложное впечатление заданных правил: каталог `.roo/rules/` содержит файл `rules.md`, но никаких правил в нём нет.

**Изменение.** Удалить файл.

**Ожидаемый результат.** Состав каталога правил соответствует их реальному содержимому.

### A8. Удаление пустого каталога правил аудита

**Каталог:** `.roo/rules-survivalpoints-audit/`

**Проблема.** Каталог содержит единственный пустой [`.roo/rules-survivalpoints-audit/rules.md`](../../.roo/rules-survivalpoints-audit/rules.md). Инструкции режима `survivalpoints-audit` фактически заданы в [`.roomodes:10-18`](../../.roomodes). При этом в системный промпт попадает блок «Rules from rules-survivalpoints-audit directories» — пустой файл создаёт иллюзию заданных правил режима.

**Изменение.** Удалить каталог вместе с файлом.

**Ожидаемый результат.** Правила режима читаются из одного источника — [`.roomodes`](../../.roomodes).

### A9. Дополнение таблицы навигатора

**Файл:** [`.roo/plans/README.md`](README.md)

**Проблема.** Таблица «Как выбрать документ» (строки 8–21) не покрывает фактические файлы в [`.roo/plans/`](.): нет `Combat/chest-uv-fix-plan.md` и `Entities/npc-names-plan.md`. `Camp/CAMP_IDEAS.md` упомянут только в разделе «Важно» (строка 59).

**Изменение.** Добавить в таблицу строки:

```markdown
| Правка UV сундука/брони | `Combat/chest-uv-fix-plan.md` |
| Имена NPC | `Entities/npc-names-plan.md` |
```

Строку про `plan.md` не добавлять — файл удаляется в C3.

**Ожидаемый результат.** Навигатор покрывает фактическое дерево; агент не делает лишних поисков.

### A10. Приведение дерева навигатора в соответствие

**Файл:** [`.roo/plans/README.md`](README.md)

**Проблема.** Дерево структуры (строки 25–47) не содержит `Combat/chest-uv-fix-plan.md` и `Entities/npc-names-plan.md`, хотя файлы существуют. Ранее отсутствовало упоминание `image.png` — сейчас уже неактуально, файл удалён.

**Изменение.** Дополнить ветки `Entities/` и `Combat/`:

```
├── Entities/                     — NPC и сущности
│   ├── plan.md
│   ├── NPC_CAMP_SIMULATION_DESIGN.md
│   └── npc-names-plan.md
├── Combat/                       — бой и боевые механики
│   ├── GUARDIAN_REWORK_PLAN.md
│   └── chest-uv-fix-plan.md
```

**Ожидаемый результат.** Дерево совпадает с `list_files` по `.roo/plans/`.

---

## 3. Волна C — удаления и восстановление ссылок

Деструктивные операции. Требуют `execute_command` и правки [`AI_CONTEXT.md`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md). Резервные копии не создаются: история — в git.

### C1. Удаление устаревшего слоя `.kilo/`

**Цель:** `.kilo/` (файлы: `kilo.jsonc`, `setup-script.ps1`, `.gitignore`)

**Проблема.** Слой дублирует конфигурацию Zoo Code и содержит битые ссылки:

- `.kilo/kilo.jsonc:3-7` — `"instructions": [".kilo/rules/minecraft.md", ".kilo/rules/codestyle.md", ".kilo/rules/git.md"]`. Каталога `.kilo/rules/` не существует.
- `.kilo/kilo.jsonc:63` — architect обязан использовать `.kilo/plans/` и инструмент `plan_exit`. Каталога нет; планы живут в `.roo/plans/`; инструмента `plan_exit` в Zoo Code нет.
- `.kilo/kilo.jsonc:73` — дубль роли `code` из [`.roomodes:22-27`](../../.roomodes).
- `.kilo/kilo.jsonc:8-27` против [`.roo/mcp.json:59-95`](../../.roo/mcp.json) — два разных Git-MCP.

**Изменение.** Удалить каталог `.kilo/` целиком.

**Обоснование решения.** Режимы заданы только в [`.roomodes`](../../.roomodes), MCP — в [`.roo/mcp.json`](../../.roo/mcp.json). Kilo Code в проекте не используется.

**Ожидаемый результат.** Один источник истины для режимов и MCP.

### C2. Удаление устаревшего индекса планов

**Цель:** `.roo/plans_inventory.txt`

**Проблема.** Выгрузка на 3042 строки содержит устаревшие пути: например `.roo\plans\CAMP_IDEAS.md`, тогда как фактический файл — [`.roo/plans/Camp/CAMP_IDEAS.md`](Camp/CAMP_IDEAS.md). Файл не упомянут ни в навигаторе, ни в правилах, и вводит в заблуждение при поиске документации.

**Изменение.** Удалить файл.

**Ожидаемый результат.** Навигатор [`.roo/plans/README.md`](README.md) — единственный индекс документации.

### C3. Удаление реализованного этапного плана

**Цель:** `.roo/plans/plan.md` — «План: Этап 3 — женский торс и отображение нагрудной брони»

**Проблема.** Файл конфликтует по имени с каноном [`.roo/plans/Entities/plan.md`](Entities/plan.md), на него ведёт ссылка из [`.roo/plans/Entities/plan.md:11`](Entities/plan.md) («Этап 3 ... вынесен в отдельный документ — `plan.md`»). После правки [`.roomodes:47`](../../.roomodes) новые планы получают уникальные имена, поэтому файл больше не пересоздаётся и остаётся мёртвым артефактом.

**Проверка факта реализации (обязательна до удаления):**

| Подтверждение | Где |
|---|---|
| Модель с выступами торса реализована | [`SurvivorBodyModel.java`](../../src/main/java/com/phian404/survivalpoints/client/SurvivorBodyModel.java) — класс, `chestLayer(boolean)`, сегменты `chest_seg0..3`, капы `chest_under0..1` |
| Параметры формы вынесены | [`SurvivorChestTuning.java`](../../src/main/java/com/phian404/survivalpoints/client/SurvivorChestTuning.java) |
| Armor-слой подключён | [`ModEntityRenderers.java:165`](../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java), `:211` — `HumanoidArmorLayer` с `PLAYER_INNER_ARMOR`/`PLAYER_OUTER_ARMOR` |
| Модели используют `chestLayer` | [`ModEntityRenderers.java:65`](../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java), `:177` |
| Функциональность задокументирована | [`AI_CONTEXT.md:172-180`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) — раздел «Геометрия торса и броня (этап 3)» |

**Вывод:** этап 3 реализован и задокументирован в `AI_CONTEXT.md`. Удаление плана не теряет информацию о реализации.

**Изменение.** Удалить файл.

**Порядок:** выполнять **после** C4, чтобы не оставить битую ссылку.

### C4. Обновление ссылки на удаляемый план

**Файл:** [`AI_CONTEXT.md:172`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md)

**Проблема.** Строка 172:

```markdown
### Геометрия торса и броня (этап 3, см. [`plan.md`](../../../../../../.roo/plans/plan.md))
```

После C3 ссылка ведёт в несуществующий файл.

**Важно:** это единственное место в проекте с такой ссылкой — проверено поиском по всем `.md` (шаблон `plans/plan\.md|\.\./plan\.md|plans_inventory|\.kilo`, найден 1 результат).

**Изменение.** Заменить строку на:

```markdown
### Геометрия торса и броня (этап 3 — реализовано)
```

**Ожидаемый результат.** Нет битых ссылок; `AI_CONTEXT.md` остаётся единственным описанием этапа 3 после удаления плана.

---

## 4. Порядок выполнения

1. **Волна A** (A1–A10) — текстовые правки `.md`, безопасны, перезагрузка не нужна.
2. **Волна C** — в порядке: C1 → C2 → C4 → C3.
   - C4 строго перед C3: сначала убрать ссылку, потом удалять цель.
3. Проверка: `git status` — ожидается удаление `.kilo/`, `.roo/plans_inventory.txt`, `.roo/plans/plan.md`, `.roo/rules/rules.md`, `.roo/rules-survivalpoints-audit/` и правки в шести `.md`-файлах.

---

## 5. Затрагиваемые файлы

**Изменяются:**

| Файл | Шаги |
|---|---|
| [`.roo/rules/anti-loop.md`](../../.roo/rules/anti-loop.md) | A1 |
| [`.roo/rules-code/codestyle.md`](../../.roo/rules-code/codestyle.md) | A2 |
| [`.roo/rules/minecraft.md`](../../.roo/rules/minecraft.md) | A3, A4, A5 |
| [`.roo/rules-code/git.md`](../../.roo/rules-code/git.md) | A6 |
| [`.roo/plans/README.md`](README.md) | A9, A10 |
| [`AI_CONTEXT.md`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) | C4 |

**Удаляются:**

| Цель | Шаги |
|---|---|
| `.roo/rules/rules.md` | A7 |
| `.roo/rules-survivalpoints-audit/` | A8 |
| `.kilo/` | C1 |
| `.roo/plans_inventory.txt` | C2 |
| `.roo/plans/plan.md` | C3 |

**Не создаётся:** шаблон `plan.md`. Новые планы получают уникальные имена `.roo/plans/<тема>-plan.md` по правилу [`.roomodes:47`](../../.roomodes). Пустой шаблон не нужен: он снова внёс бы конфликт имён с каноном [`.roo/plans/Entities/plan.md`](Entities/plan.md) и не используется ни одним режимом.

---

## 6. Критерии готовности

- [ ] Правило `update_todo_list` доступно всем трём режимам: присутствует в [`.roo/rules/anti-loop.md`](../../.roo/rules/anti-loop.md), отсутствует в [`.roo/rules-code/codestyle.md`](../../.roo/rules-code/codestyle.md).
- [ ] В [`.roo/rules/minecraft.md`](../../.roo/rules/minecraft.md) три отдельных раздела: Стиль / Версия API / Верификация.
- [ ] В политике верификации Forge-классы проверяются по Gradle-кэшу, vanilla — через MCP, при недоступности обоих требуется явно сообщить об ограничении.
- [ ] Норма «группировать правки и проверки» существует в одном файле; второй содержит ссылку.
- [ ] Каталог `.roo/rules/` не содержит пустых файлов; каталога `.roo/rules-survivalpoints-audit/` нет.
- [ ] Таблица и дерево [`.roo/plans/README.md`](README.md) совпадают с выводом `list_files` по `.roo/plans/`.
- [ ] Каталога `.kilo/` нет.
- [ ] Файла `.roo/plans_inventory.txt` нет.
- [ ] Файла `.roo/plans/plan.md` нет; ссылка на него в [`AI_CONTEXT.md`](../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) отсутствует.
- [ ] `git status` не показывает неожиданных изменений.

---

## 7. Риски

| Риск | Оценка | Решение |
|---|---|---|
| Потеря несохранённого содержимого при удалении `.kilo/`, `plans_inventory.txt`, `plan.md` | Средний | Убедиться, что файлы закоммичены; откат через `git restore` |
| Битые ссылки после C3 | Средний | C4 выполняется строго до C3; предварительно проверено, что ссылка единственная |
| Расширение правил для Forge-верификации (A4) снижает требовательность к MCP | Низкий | В формулировке сохранено требование верификации; меняется только источник сверки |
| Расширение навигатора (A9) конфликтует с правилом «не открывай все файлы подряд» | Низкий | Формулировать как «куда идти при задаче X», а не как полный каталог — уже учтено |
| Удаление пустого `rules.md` (A7, A8) может сломать ожидание расширения на наличие каталога | Низкий | Правила режима `survivalpoints-audit` заданы в [`.roomodes:10-18`](../../.roomodes); каталог `rules-<slug>/` не обязателен |
| Появление нового дубля при переносе правила (A1/A2) | Низкий | A2 удаляет исходный блок в том же проходе |

---

## 8. Вне области этого плана

- Java-код мода не изменяется.
- `.roomodes` не изменяется (шаги 1–3 уже выполнены).
- [`.roo/mcp.json`](../../.roo/mcp.json) не изменяется.
- Режимы `ask`, `debug`, `orchestrator` вне области по решению владельца.
