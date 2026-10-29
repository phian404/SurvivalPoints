# Волна 2: Якорь + генерация лагеря

> **Цель:** материальный лагерь с радио вышкой как якорем, забором, воротами, вышками, складом.
> **Источник:** [`DESIGN_CAMP_SYSTEM.md`](DESIGN_CAMP_SYSTEM.md) §3.2, §10, §18; [`DESIGN_CAMP_SOCIAL.md`](DESIGN_CAMP_SOCIAL.md) §9.

---

## 1. Что делаем

### 1.1 Блоки лагеря

| Блок | Класс | BE | Назначение |
|---|---|---|---|
| Радио вышка | `CampRadioBlock` | `CampRadioBlockEntity` | Якорь лагеря, связь, призыв |
| Забор | `CampFenceBlock` | — | Периметр |
| Ворота | `CampGateBlock` | `CampGateBlockEntity` | Вход, авто-закрытие ночью |
| Вышка | `CampWatchtowerBlock` | `CampWatchtowerBlockEntity` | Обзор 360°, бонус к стрельбе |
| Склад | `CampStorageBlock` | `CampStorageBlockEntity` | Ресурсы лагеря |

### 1.2 Генерация лагеря

- **Блюпринты:** ASCII-сетка (3–4 варианта: аванпост, крепость, посёлок, развилка)
- **Парсер:** `CampBlueprintParser` (~60 строк)
- **Трансформации:** поворот/зеркало/масштаб (~80 строк)
- **Посадка:** `CampGenerator` (~80 строк)
- **Процедурная рассадка:** ворота, вышки, посты (~120 строк)

### 1.3 Команды

- `/sp_camp generate <name> <radius>` — создать лагерь с блоками
- `/sp_camp show <id>` — показать границу лагеря (частицы)

---

## 2. Структура файлов

### 2.1 Новые файлы

```
src/main/java/com/phian404/survivalpoints/camp/
├── block/
│   ├── CampRadioBlock.java
│   ├── CampFenceBlock.java
│   ├── CampGateBlock.java
│   ├── CampWatchtowerBlock.java
│   └── CampStorageBlock.java
├── blockentity/
│   ├── CampRadioBlockEntity.java
│   ├── CampGateBlockEntity.java
│   ├── CampWatchtowerBlockEntity.java
│   └── CampStorageBlockEntity.java
├── CampBlueprintParser.java
└── CampGenerator.java

src/main/java/com/phian404/survivalpoints/init/
└── ModBlocks.java

src/main/resources/assets/survivalpoints/
├── blockstates/
│   ├── camp_radio.json
│   ├── camp_fence.json
│   ├── camp_gate.json
│   ├── camp_watchtower.json
│   └── camp_storage.json
├── models/block/
│   ├── camp_radio.json
│   ├── camp_fence.json
│   ├── camp_gate.json
│   ├── camp_watchtower.json
│   └── camp_storage.json
└── textures/block/
    ├── camp_radio.png
    ├── camp_fence.png
    ├── camp_gate.png
    ├── camp_watchtower.png
    └── camp_storage.png
```

### 2.2 Изменяемые файлы

- [`CampRecord.java`](../../../src/main/java/com/phian404/survivalpoints/camp/CampRecord.java) — добавить поля `gatePos`, `towerPos`, `storagePos`, `farmCenter`
- [`CampManager.java`](../../../src/main/java/com/phian404/survivalpoints/camp/CampManager.java) — метод `generateCampStructure(record)`
- [`CampDebugCommand.java`](../../../src/main/java/com/phian404/survivalpoints/init/CampDebugCommand.java) — команда `generate`
- [`ModBlocks.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) — регистрация блоков, предметов блоков и блок-сущностей
- [`SurvivalPointsMod.java`](../../../src/main/java/com/phian404/survivalpoints/SurvivalPointsMod.java) — вызов регистрации блоков

---

## 3. Детали реализации

### 3.1 Радио вышка (якорь)

**Блок:**
- `CampRadioBlock extends BaseEntityBlock` — ✅ реализовано
- `BlockState` — только `powered` (день/ночь) — ✅ реализовано; `active` **не существует**
- `onPlace` — создать лагерь, если рядом нет — ⬜ не реализовано
- `onRemove` — разрушить лагерь, если это якорь — ⬜ не реализовано

**BE:**
- `CampRadioBlockEntity extends BlockEntity` — ✅ реализовано
- Хранит: `campId`, `charge` (редстоун/ресурс) — ✅ реализовано
- `tick()` — переключает `powered` по времени суток, уменьшает `charge` на 1 за тик — ✅ реализовано
- Призыв выжившего раз в 24000 тиков (`CAMP_RADIO_CALL_INTERVAL`) — ⬜ не реализовано:
  константа есть в `CampTuning`, вызова в BE нет
- `onRightClick` — открыть панель лагеря (статистика, состав, радио-объявление) — ⬜ не реализовано;
  сейчас ПКМ выводит в чат название привязанного лагеря

**Логика:**
- Лагерь существует, пока стоит вышка
- Уничтожена → распад лагеря (посты и привязки исчезают, склад выпадает в сундук)
- Ночью выключена

### 3.2 Забор

**Блок:**
- `CampFenceBlock extends WallBlock` (либо `IronBarsBlock`)
- Ванильная текстура + тонировка
- Разрушаемость: 3–5 ударов

> Класса `IronFenceBlock` в Minecraft 1.20.1 нет; ближайшие ванильные варианты — `WallBlock` и `IronBarsBlock`.

### 3.3 Ворота

**Блок:**
- `CampGateBlock extends DoorBlock`
- `BlockState` — `open` (день/ночь), `powered` (авто-закрытие)

**BE:**
- `CampGateBlockEntity extends BlockEntity`
- `tick()` — ночью закрыть, днём открыть
- `onRightClick` — открыть/закрыть вручную

**Логика:**
- Днём открыты, ночью закрыты
- Игрок может открыть вручную (если не закрыт лагерь)

### 3.4 Вышка

**Блок:**
- `CampWatchtowerBlock extends Block`
- Ванильная текстура + тонировка
- Высота: 4–5 блоков

**BE:**
- `CampWatchtowerBlockEntity extends BlockEntity`
- Хранит: `campId`, `type` (TOWER)
- `onRightClick` — показать информацию (тип поста, бонус)

**Логика:**
- Обзор 360°, бонус к дальности стрельбы (+30%), бонус к урону (+20%)
- Не спускается, пока не объявлена тревога

### 3.5 Склад

**Блок:**
- `CampStorageBlock extends ChestBlock`
- Ванильный сундук + тонировка
- Слоты: 27 (как ванильный сундук)

**BE:**
- `CampStorageBlockEntity extends ChestBlockEntity`
- Хранит: `campId`, `resources` (food, bread, ammo, weapons, materials)
- `tick()` — переработка пшеницы в хлеб (3 пшеницы → 1 хлеб)
- `onRightClick` — показать ресурсы

**Логика:**
- Слоты видны игроку (инвентарь лагеря)
- Ресурсы тратятся на починку, ловушки, снабжение стражей

### 3.6 Генерация лагеря

**Блюпринты:**

```
// F=забор  G=ворота  T=вышка  W=склад  R=радио  B=доска  .=пусто
F F F F F F F
F T . . . T F
F . W R B . F
F . . G . . F
F F F F F F F
```

**Парсер:**
- Читает ASCII-сетку
- Возвращает `String[]` + метаданные (радиус, точки входа)
- Хранит в коде (на первом этапе), перенести в ресурсы позже

**Трансформации:**
- Поворот 0/90/180/270
- Зеркалирование
- Масштабирование (маленький/средний/большой)

**Посадка:**
- `CampGenerator.generate(record)` — создаёт блоки в мире
- Использует `level.setBlock` + `level.setBlockEntity`
- Сохраняет позиции в `CampRecord` (gatePos, towerPos, storagePos, farmCenter)

**Процедурная рассадка:**
- Ворота: 1–2 пары, на одной из 4 сторон
- Вышки: на углах, ≥2
- Радио: в центре (или в тылу)
- Склад: рядом с радио
- Забор: по периметру

### 3.7 Команды

**`/sp_camp generate <name> <radius>`**
- Проверяет, можно ли создать лагерь
- Создаёт `CampRecord`
- Генерирует структуру лагеря
- Показывает частицы границы лагеря

**`/sp_camp show <id>`**
- Показывает границу лагеря частицами (кольцо по периметру, точки постов)

---

## 4. Проверка готовности

Фактическое состояние на момент сверки:

- ⬜ Лагерь создаётся командой `/sp_camp generate` — команды нет, есть `/sp_camp create` без генерации блоков
- ⬜ Лагерь виден в мире как укреплённая стоянка (забор, ворота, вышки, склад) — блоков нет
- ⚠️ Радио вышка: [`CampRadioBlock`](../../../src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java) и [`CampRadioBlockEntity`](../../../src/main/java/com/phian404/survivalpoints/camp/blockentity/CampRadioBlockEntity.java) реализованы и зарегистрированы, но **якорь не автоматический**: создание и распад лагеря от установки/разрушения вышки не реализованы
- ⬜ Генерация лагеря: [`CampBlueprintParser`](../../../src/main/java/com/phian404/survivalpoints/camp/CampBlueprintParser.java) разбирает сетку, но [`CampGenerator.generate(...)`](../../../src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java) — пустой каркас без постановки блоков и нигде не вызывается
- ⬜ Ночью ворота закрываются, радио выключается — ворот нет; радио переключает `POWERED` по времени суток
- ⬜ Склад хранит ресурсы — блока склада нет
- ✅ Лагерь переживает перезапуск мира (данные в `CampSavedData`)

---

## 5. Следующие шаги

- Волна 3: посты + привязка стражей, зона тревоги, ALERT/DEFEND, ночной режим
- Волна 4: еда (склад-блок, фермер-житель, грузчик, хлеб, кормление стражей)
- Волна 5: глава, диалог, торговля, репутация (UI-волна I)
