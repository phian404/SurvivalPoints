# CreativeTab — план

> Новая вкладка в креативе для мода SurvivalPoints

---

## Содержание вкладки

| Элемент | Тип | Источник |
|---|---|---|
| Яйцо призыва стража (`BaseGuardianEntity`) | Spawn Egg | [`ModEntities.java`](src/main/java/com/phian404/survivalpoints/init/ModEntities.java) |
| Радио вышка (`CampRadioBlock`) | Block | [`ModBlocks.java`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) |
| Забор (`CampFenceBlock`) | Block | Будущий |
| Ворота (`CampGateBlock`) | Block | Будущий |
| Вышка (`CampWatchtowerBlock`) | Block | Будущий |
| Склад (`CampStorageBlock`) | Block | Будущий |

---

## Реализация

### 1. Регистрация вкладки

```java
// SurvivalPointsMod.java или новый ModCreativeTab.java
public static final CreativeModeTab SURVIVALPOINTS_TAB = new CreativeModeTab("survivalpoints") {
    @Override
    public ItemStack makeIcon() {
        return new ItemStack(ModBlocks.CAMP_RADIO.get());
    }
};
```

### 2. Регистрация предметов

```java
// Для каждого блока
Item.Properties props = new Item.Properties().tab(SURVIVALPOINTS_TAB);
```

### 3. Яйцо призыва стража

```java
// ModEntities.java — добавить SpawnEggItem для BaseGuardianEntity
```

---

## Файлы

- [`SurvivalPointsMod.java`](src/main/java/com/phian404/survivalpoints/SurvivalPointsMod.java) — регистрация вкладки
- [`ModBlocks.java`](src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) — регистрация блоков в вкладку
- [`ModEntities.java`](src/main/java/com/phian404/survivalpoints/init/ModEntities.java) — яйцо призыва
