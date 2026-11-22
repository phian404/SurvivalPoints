# Радио вышка — детальный разбор

> Волна 2, блок `CampRadioBlock` + `CampRadioBlockEntity`

---

## 1. Технические аспекты

### 1.1 Блок (`CampRadioBlock`)

| Аспект | Факт в коде | Файл |
|---|---|---|
| Класс | `CampRadioBlock extends BaseEntityBlock` | [`CampRadioBlock.java`](../../../src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java) |
| Свойства | `Properties.of().strength(3.0F, 6.0F).requiresCorrectToolForDrops()` | там же |
| BlockState | `POWERED` (`BooleanProperty`), по умолчанию `false` | там же |
| BE | `CampRadioBlockEntity extends BlockEntity` | [`CampRadioBlockEntity.java`](../../../src/main/java/com/phian404/survivalpoints/camp/blockentity/CampRadioBlockEntity.java) |
| Регистрация | `ModBlocks.CAMP_RADIO`, `CAMP_RADIO_ITEM`, `CAMP_RADIO_BE` | [`ModBlocks.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) |

> **Примечание.** Класс `Material` в 1.20.1 отсутствует; материал блока не задаётся — используются
> свойства `Properties.of()`.

### 1.2 BE (`CampRadioBlockEntity`)

| Поле | Тип | Назначение |
|---|---|---|
| `campId` | `UUID` | Привязка к лагерю |
| `charge` | `int` | Заряд (редстоун/ресурс) |

**Конструктор.** Единственный — `CampRadioBlockEntity(BlockPos pos, BlockState state)`, вызывает
`super(ModBlocks.CAMP_RADIO_BE.get(), pos, state)`. Конструктор без аргументов не добавлять:
фабрика типа BE — `CampRadioBlockEntity::new` в
[`ModBlocks`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java:33).

**Тикер блока.** `CampRadioBlock.getTicker(...)` обязан проверить
`type == ModBlocks.CAMP_RADIO_BE.get()` и вернуть `null`, если тип не совпал или уровень клиентский;
иначе возвращается тикер, вызывающий `CampRadioBlockEntity.tick(...)`
([`CampRadioBlock`](../../../src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java:65)).

**Тик (`tick()`):**
- Днём (`!level.isNight()`): работает
- Ночью: выключена
- `charge` уменьшается на 1 каждый тик, пока больше нуля
- Призыв выжившего раз в 24000 тиков (`CAMP_RADIO_CALL_INTERVAL`,
  [`CampTuning`](../../../src/main/java/com/phian404/survivalpoints/camp/CampTuning.java:67)) —
  **не реализован**: константа существует, вызова в BE нет

### 1.3 Логика якоря

**Реализовано в коде:**

- `CampRecord.anchor` — поле позиции якоря, сохраняется в NBT лагеря.
- ПКМ (`use()`) → сообщение в чат с названием лагеря, если `CampManager.findCampAt(...)` вернул запись.
- `tick(...)` в BE переключает `POWERED` по времени суток и уменьшает `charge` на 1 за тик.

**Целевой дизайн (в коде отсутствует):**

- Автоматическое создание лагеря при установке вышки.
- Автоматический распад лагеря при её разрушении.

В [`CampRadioBlock`](../../../src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java)
нет `onPlace`/`setPlacedBy`/`onRemove`/`playerWillDestroy`, нет подписки на `BlockEvent`.
Создание лагеря сейчас выполняется только вручную командой `/sp_camp create`
через [`CampDebugCommand`](../../../src/main/java/com/phian404/survivalpoints/init/CampDebugCommand.java).

---

## 2. Визуальные аспекты

### 2.1 Модель (`models/block/camp_radio.json`)

```json
{
  "parent": "block/cube_all",
  "textures": {
    "all": "survivalpoints:block/camp_radio"
  }
}
```

**Варианты:**
- Простой куб (металл + антенна) — минимально
- Сложная модель (антенна, провода, свет) — красиво, но дороже
- **Рекомендация:** простой куб с текстурой металла + антенна (как у ванильного `iron_block` с модификацией)

### 2.2 Текстура (`textures/block/camp_radio.png`)

- Базовая текстура: серый металл (`#808080`)
- Детали: антенна (чёрная линия), свет (жёлтый круг)
- Размер: 16x16 (ванильный стандарт)

### 2.3 Blockstates (`blockstates/camp_radio.json`)

```json
{
  "variants": {
    "powered=true": { "model": "survivalpoints:block/camp_radio_on" },
    "powered=false": { "model": "survivalpoints:block/camp_radio_off" }
  }
}
```

**Состояния:**
- `powered=true` — работает (днём)
- `powered=false` — выключена (ночью)

### 2.4 Анимация

- **Дешёвая:** смена текстуры (powered) — 0 строк кода
- **Средняя:** частицы (искры) при работе — ~10 строк
- **Дорогая:** световой эффект (шейдер) — не рекомендуется для первой версии

---

## 3. Интеграция с лагерем

### 3.1 Связь с `CampRecord`

```java
// CampRecord
private BlockPos anchor;  // позиция радио вышки
```

### 3.2 Связь с `CampSavedData`

```java
// CampSavedData
public CampRecord getCamp(UUID id) { ... }
```

### 3.3 События

| Событие | Статус | Действие |
|---|---|---|
| `BlockEvent.PlaceEvent` | ⬜ не реализовано | Целевое: установка вышки → создание лагеря |
| `BlockEvent.BreakEvent` | ⬜ не реализовано | Целевое: разрушение вышки → распад лагеря |
| `ServerTickEvent` | ✅ реализовано | Тик лагеря (режим, таймеры) через `CampManager.onServerTick(...)`, вызов в [`ModEntityEvents`](../../../src/main/java/com/phian404/survivalpoints/init/ModEntityEvents.java) |
| `BlockEntityTicker` блока | ✅ реализовано | `CampRadioBlock.getTicker(...)` → `CampRadioBlockEntity.tick(...)` |

Подписки на `BlockEvent` в проекте нет. Поиск `onRemove|playerWillDestroy|BreakEvent|PlaceEvent`
по `src/main/java/com/phian404/survivalpoints/init/` даёт только `findCampAt` в отладочной команде.

---

## 4. Стоимость

Статус сверен с рабочим деревом. Отметка ✅ означает «файл есть и выполняет заявленную работу»,
⬜ — «файл есть, но работа не реализована».

| Часть | Строк | Статус |
|---|---|---|
| Блок (`CampRadioBlock`) | ~60 | ✅ [`camp/block/CampRadioBlock.java`](../../../src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java) |
| BE (`CampRadioBlockEntity`) | ~80 | ✅ [`camp/blockentity/CampRadioBlockEntity.java`](../../../src/main/java/com/phian404/survivalpoints/camp/blockentity/CampRadioBlockEntity.java) |
| Регистрация (`ModBlocks`) | ~30 | ✅ [`ModBlocks.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) |
| Модель (`models/block/camp_radio.json`) | ~10 | ✅ простой `cube_all` |
| Blockstates (`blockstates/camp_radio.json`) | ~15 | ✅ оба состояния `powered` указывают на одну модель |
| Текстура (`textures/block/camp_radio.png`) | — | ✅ файл существует |
| Генерация (`CampGenerator`) | ~80 | ⬜ **каркас**: [`CampGenerator.generate(...)`](../../../src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java) содержит только комментарии, блоки не ставит |
| Парсер блюпринтов (`CampBlueprintParser`) | ~60 | ✅ разбирает сетку, но не связан с `CampGenerator` |

`CampGenerator.generate(ServerLevel, CampRecord)` нигде не вызывается: поиск по `src/`
находит только объявление метода.

---

## 6. Дизайн-решения (перенесено из §8 DESIGN_CAMP_SOCIAL.md)

### 6.1 Правила якоря

- **Якорь:** лагерь существует, пока стоит его радио вышка; вышка = сердце лагеря.
- **Создание:** игрок ставит радио вышку → создаётся лагерь (если не превышен потолок и нет своего лагеря).
- **Потолок:** мировых лагерей — 3–4 на мир; личный лагерь — 1 на игрока.
- **Уничтожение:** вышка разрушена → лагерь распадается: посты и привязки исчезают, склад выпадает в сундук (иначе дроп на землю), стражи становятся «свободными».
- **Восстановление:** вышка на прежнем месте → лагерь восстанавливается с тем же `campId`; репутация сохраняется.

### 6.2 Почему вышка, а не отдельный маяк

- Минус блоков: не нужен отдельный «маяк».
- Логическая связка: вышка = связь = призыв выживших = якорь.
- Драматургия: построить вышку = создать лагерь.

### 6.3 Данные BE (из §8.3)

Хранит: `campId`, радиус, режим (день/ночь), заряд (нужен для работы), «последний вызов».

- ПКМ: открыть панель лагеря (статистика, состав, радио-объявление) — простой экран.
- Разрушение: `BlockEvent.BreakEvent` → распад лагеря.
- Ночью: не работает (заряд тратится только днём).
