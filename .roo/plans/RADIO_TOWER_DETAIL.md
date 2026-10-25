# Радио вышка — детальный разбор

> Волна 2, блок `CampRadioBlock` + `CampRadioBlockEntity`

---

## 1. Технические аспекты

### 1.1 Блок (`CampRadioBlock`)

| Аспект | Решение | Файл |
|---|---|---|
| Класс | `Block` (не `BaseEntityBlock`) | [`CampRadioBlock.java`](src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java) |
| Материал | `Material.METAL` | — |
| Прочность | 3.0F / 6.0F | — |
| BE | `CampRadioBlockEntity` | [`CampRadioBlockEntity.java`](src/main/java/com/phian404/survivalpoints/camp/blockentity/CampRadioBlockEntity.java) |
| Регистрация | `ModBlocks.CAMP_RADIO` | [`ModBlocks.java`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) |

### 1.2 BE (`CampRadioBlockEntity`)

| Поле | Тип | Назначение |
|---|---|---|
| `campId` | `UUID` | Привязка к лагерю |
| `charge` | `int` | Заряд (редстоун/ресурс) |

**Тик (`tick()`):**
- Днём (`!level.isNight()`): работает
- Ночью: выключена
- Раз в 24000 тиков (`CAMP_RADIO_CALL_INTERVAL`): призыв выжившего (волна 6)

### 1.3 Логика якоря

- Лагерь существует, пока стоит вышка (`CampRecord.anchor`)
- Уничтожена (`BlockEvent.BreakEvent`) → распад лагеря (`CampSavedData.removeCamp`)
- ПКМ (`use()`) → показать информацию о лагере

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

| Событие | Действие |
|---|---|
| `BlockEvent.PlaceEvent` | Установка вышки → создание лагеря |
| `BlockEvent.BreakEvent` | Разрушение вышки → распад лагеря |
| `ServerTickEvent` | Тик лагеря (режим, таймеры) |

---

## 4. Стоимость

| Часть | Строк | Статус |
|---|---|---|
| Блок (`CampRadioBlock`) | ~60 | ✅ |
| BE (`CampRadioBlockEntity`) | ~80 | ✅ |
| Регистрация (`ModBlocks`) | ~30 | ✅ |
| Модель (`camp_radio.json`) | ~10 | ⬜ |
| Текстура (`camp_radio.png`) | — | ⬜ |
| Blockstates (`camp_radio.json`) | ~15 | ⬜ |
| Генерация (`CampGenerator`) | ~80 | ⬜ |

---

## 5. Открытые вопросы

1. **Модель:** простой куб или сложная с антенной?
2. **Текстура:** ванильная (`iron_block`) или своя?
3. **Анимация:** только смена текстуры или частицы?
4. **Звук:** ванильный (`SoundEvents`) или новый?
