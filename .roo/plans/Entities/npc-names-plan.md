# План: постоянные имена NPC

> Статус: финализирован, ожидает реализации.
> Проект: SurvivalPoints, Minecraft Forge 1.20.1, Java 17.
> Связанные документы: [`plan.md`](plan.md) (канон архитектуры NPC), [`NPC_CAMP_SIMULATION_DESIGN.md`](NPC_CAMP_SIMULATION_DESIGN.md), [`AI_CONTEXT.md`](../../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md).

---

## 1. Текущая архитектура (подтверждено кодом)

### 1.1 Общая база NPC — [`BaseSurvivorEntity`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorEntity.java)

- NBT: [`addAdditionalSaveData`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorEntity.java:222) пишет `IdentityId`, `Role`, `Skin`, `Gender`, `Scale`, `CampId`, `CampPostId`.
- [`readAdditionalSaveData`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorEntity.java:238) читает их в строгом порядке: `Skin` → `Gender` → `Scale` (диапазон роста зависит от пола).
- **Имени в NBT нет.**

### 1.2 Текущее состояние имени

Поиск `setCustomName|getCustomName|hasCustomName|CustomNameVisible` по `src/main/java` даёт **единственное** совпадение — [`copySharedState`](../../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:145):

```java
if (source.hasCustomName()) {
    replacement.setCustomName(source.getCustomName());
    replacement.setCustomNameVisible(source.isCustomNameVisible());
}
```

То есть имя переносится при смене роли, но **нигде не назначается**. Обычный NPC сейчас безымянный, тег не отображается.

### 1.3 Точки создания NPC

| Точка | Файл:строка | Особенность |
|---|---|---|
| `RoleAssignmentService.create` | [`RoleAssignmentService.java:51`](../../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:51) | `rollRandomSkin()` + `rollRandomScale()` при `!isClientSide`; **`finalizeSpawn` не вызывается** |
| `BaseSurvivorNpcEntity.finalizeSpawn` | [`BaseSurvivorNpcEntity.java:60`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorNpcEntity.java:60) | `rollRandomSkin()` + `rollRandomScale()` при `!isClientSide && dataTag == null` |
| `BaseGuardianEntity.finalizeSpawn` | [`BaseGuardianEntity.java:1505`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1505) | то же |
| Яйцо призыва | `ForgeSpawnEggItem` → `EntityType.spawn(...)` | проходит через `finalizeSpawn` |

### 1.4 Смена роли — [`RoleAssignmentService`](../../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java)

- [`reassign`](../../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:93): создать замену → `copySharedState` → `addFreshEntity` → `discard` исходника.
- Порядок корректен: данные переносятся **до** попадания новой сущности в мир.

### 1.5 Отображение имени

- Ни один рендерер в [`ModEntityRenderers`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java) не переопределяет `shouldShowName`; тег рисуется ванильно внутри `super.render`.

### 1.6 Проверенный API (MCP `minecraft-modding`, 1.20.1, mojang)

- `Entity.setCustomName(Component)`, `getCustomName()`, `hasCustomName()`, `setCustomNameVisible(boolean)`, `isCustomNameVisible()` — существуют.
- Имя хранится в `Entity.DATA_CUSTOM_NAME` (`EntityDataSerializers.OPTIONAL_COMPONENT`) — **синхронизация клиенту автоматическая**, отдельный `EntityDataAccessor` не нужен.
- NBT-ключи `CustomName` (JSON Component) и `CustomNameVisible` пишутся и читаются в `Entity.save`/`Entity.load` **до** вызова `addAdditionalSaveData`/`readAdditionalSaveData`.
- `LivingEntity.shouldShowName()` → `isCustomNameVisible()`.
- `EntityRenderer.render` рисует тег при `shouldShowName(entity) && hasCustomName()`.
- `MobRenderer.shouldShowName(Mob)` — `protected`, доступен для переопределения (не требуется в этом плане).

---

## 2. Предлагаемое решение

### 2.1 Хранение

Только ванильный `CustomName`. Причины:

- одно поле истины, нет дублирования между своим тегом и ванильным;
- автосохранение и автосинхронизация клиенту;
- ноль изменений в `addAdditionalSaveData` (ванильный `Entity.save` уже пишет `CustomName`).

Структурированное хранение (`FirstName`/`LastName` отдельно) **не вводится**: потребность появится только с диалогами и репутацией — отдельный этап.

### 2.2 Генерация

Новый класс `entity/role/SurvivorNames`:

- `private static final String[] MALE_FIRST`
- `private static final String[] FEMALE_FIRST`
- `private static final String[] LAST_NAMES` (общие для обоих полов)
- `public static String random(SurvivorGender gender, RandomSource random)` → `"First Last"`.

Расширение = дописывание строк в массив, основная логика не меняется. Язык — английский, согласуется с лором лидеров (`Cole Reed`, `Martha Hill`, `Mike Carter`, `Sam Walker`).

### 2.3 Назначение

Идемпотентный метод в `BaseSurvivorEntity`:

```java
public void ensureName() {
    if (this.hasCustomName()) {
        return;
    }
    this.setCustomName(Component.literal(SurvivorNames.random(this.getGender(), this.getRandom())));
    this.setCustomNameVisible(CUSTOM_NAME_VISIBLE);
}
```

Вызовы:

1. `RoleAssignmentService.create` — в блоке `!isClientSide`, после `rollRandomScale()` (пол уже назначен).
2. `BaseSurvivorNpcEntity.finalizeSpawn` — в блоке `!isClientSide && dataTag == null`, после `rollRandomScale()`.
3. `BaseGuardianEntity.finalizeSpawn` — то же.
4. `BaseSurvivorEntity.readAdditionalSaveData` — после восстановления `Gender` (и `Scale`), для старых NPC без имени.

Почему все четыре: яйцо идёт через `finalizeSpawn` (NBT нет); `create` не вызывает `finalizeSpawn`; старые сохранения проходят только через `readAdditionalSaveData`. Идемпотентность исключает двойную генерацию.

### 2.4 Отображение

Константа `BaseSurvivorEntity.CUSTOM_NAME_VISIBLE = true`; флаг вынесен в константу, чтобы позже читать его из конфига без правки логики.

Радиус показа — 16 блоков, реализован в рендерере: в оба анонимных `MobRenderer` добавлено переопределение `shouldShowName(...)` = `super.shouldShowName(entity) && distanceToSqr(player) <= 16²` (константа `NAME_VISIBLE_RADIUS_SQR`, общий помощник `shouldShowNameWithinRange`). Проверка клиентская, относительно `Minecraft.getInstance().player`, поэтому каждый игрок видит имена по своему расстоянию; серверной синхронизации и тика на сущность не требуется. `CustomName`, `ensureName()` и NBT не меняются.

### 2.5 Смена роли

Изменений не требуется: [`copySharedState`](../../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:145) уже переносит `CustomName` и флаг видимости до `addFreshEntity`. Добавляется только поясняющий комментарий: имя теперь всегда присутствует.

### 2.6 Будущие лидеры

Ничего не закладывается сейчас. Точка расширения — сам `ensureName()`: позже фиксированное имя добавляется ветвью перед случайной генерацией, без изменения вызовов. Система лидеров, диалоги, квесты и репутация не реализуются.

---

## 3. Изменяемые файлы

| Файл | Назначение изменения |
|---|---|
| `src/main/java/com/phian404/survivalpoints/entity/role/SurvivorNames.java` (новый) | Списки имён и фамилий; `random(SurvivorGender, RandomSource)` |
| [`BaseSurvivorEntity`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorEntity.java) | Константа `CUSTOM_NAME_VISIBLE`; метод `ensureName()`; вызов в `readAdditionalSaveData` после восстановления пола и роста |
| [`BaseSurvivorNpcEntity`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorNpcEntity.java:60) | Вызов `ensureName()` в блоке `!isClientSide && dataTag == null` |
| [`BaseGuardianEntity`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1505) | То же |
| [`RoleAssignmentService`](../../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:51) | Вызов `entity.ensureName()` в блоке `!isClientSide`; комментарий в `copySharedState` |
| [`AI_CONTEXT.md`](../../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) | Одна строка в разделе «NPC и роли»: имя назначается один раз и хранится в `CustomName` |

Рендерер, `ModEntities`, `ModEventBusEvents` не меняются.

---

## 4. Порядок реализации

### Этап 1 — `SurvivorNames`

- Создать класс со списками и `random(...)`.
- **Готово:** компилируется; `random` не возвращает пустую строку; для `MALE` и `FEMALE` первые имена берутся из разных массивов.

### Этап 2 — `BaseSurvivorEntity`

- Константа `CUSTOM_NAME_VISIBLE`, метод `ensureName()`, вызов в `readAdditionalSaveData`.
- **Готово:** старый NPC без `CustomName` получает имя при загрузке; повторная загрузка имя не меняет.

### Этап 3 — точки первичного создания

- `finalizeSpawn` у обоих классов + `RoleAssignmentService.create`.
- **Готово:** яйцо призыва и создание через сервис дают именованного NPC; безымянных NPC не остаётся.

### Этап 4 — проверка смены роли и отображения

- Ничего не менять, только проверить и добавить комментарий.
- **Готово:** после арбалета имя, флаг видимости, пол, скин и рост те же; в мире одна сущность.

### Этап 5 — компиляция и игровые сценарии

- `gradlew.bat compileJava`, затем `gradlew.bat runClient` по §6.
- **Готово:** сборка успешна, все сценарии §6 проходят.

---

## 5. Совместимость

- **Старые сохранения.** NPC без `CustomName` получает имя один раз в `readAdditionalSaveData` и сохранит его при следующей записи NBT. Уже именованные NPC не затрагиваются (`hasCustomName()`).
- **Смена роли.** Имя и флаг видимости переносятся существующим кодом; порядок «перенести → добавить в мир → удалить исходник» корректен.
- **Пол.** Списки выбираются по восстановленному `Gender`; в `readAdditionalSaveData` вызов стоит после `Skin` и `Gender`, поэтому миграция старых сохранений (пол по скину) тоже даёт согласованное имя.
- **Будущие лидеры.** Фиксированное имя добавляется одной ветвью в `ensureName()`; вызовы не меняются.
- **Отсутствие новых NBT-полей.** Старые миры читаются без миграций; новые теги не появляются, кроме ванильного `CustomName`.

---

## 6. Проверки

### 6.1 Компиляция

```bat
gradlew.bat compileJava
```

### 6.2 Игровые сценарии (`gradlew.bat runClient`)

1. Яйца `base_survivor` и `base_guardian`: у каждого NPC есть имя, тег виден над головой.
2. Перезаход в мир: имя не изменилось, тег виден.
3. Смена роли арбалетом: имя, тег, пол, скин и рост те же; в мире ровно одна сущность.
4. Старое сохранение (NPC без имени): имя появляется при загрузке; после перезахода то же самое.
5. Несколько спавнов подряд: имена различаются; женские NPC получают женские имена.
6. Клиент и dedicated server: имя одинаковое на сервере и клиенте (синхронизация через `DATA_CUSTOM_NAME`).
7. `copySharedState` при `hasCustomName() == false`: не должно происходить — проверить, что после этапа 3 такого состояния не возникает.

---

## 7. Риски и открытые вопросы

| Риск / вопрос | Решение / статус |
|---|---|
| Коллизии имён при малом пуле списков | Допустимо: имя — идентификатор личности, уникальным ключом остаётся `identityId`. Списки расширяются без правки логики. |
| Генерация имени на клиенте | Не происходит: `readAdditionalSaveData` вызывается на сервере; клиент получает имя через `DATA_CUSTOM_NAME`. Проверяется сценарием 6. |
| Имя не включает persistence | `setCustomName` сам по себе не делает NPC постоянным. Если NPC должен переживать выгрузку чанков — отдельное решение через `setPersistenceRequired`, сейчас **не** входит в объём. |
| `setCustomNameVisible(true)` и читаемость | Принято владельцем: тег всегда над головой. Флаг в константе, позже выносится в конфиг. |
| Скрытие имени для враждебных/скрытных NPC | Не требуется сейчас; при необходимости решается флагом видимости на конкретной сущности. |
