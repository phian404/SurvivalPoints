# CreativeTab — план

> Вкладка в креативе для мода SurvivalPoints.
> **Статус:** реализовано. Документ обновлён под фактический код 1.20.1.

---

## Содержание вкладки

| Элемент | Тип | Источник | Статус |
|---|---|---|---|
| Яйцо призыва стража (`BaseGuardianEntity`) | Spawn Egg | [`ModEntities.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModEntities.java) | ✅ |
| Яйцо призыва выжившего (`BaseSurvivorNpcEntity`) | Spawn Egg | [`ModEntities.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModEntities.java) | ✅ |
| Радио вышка (`CampRadioBlock`) | Block | [`ModBlocks.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) | ✅ |
| Забор (`CampFenceBlock`) | Block | Будущий | ⬜ |
| Ворота (`CampGateBlock`) | Block | Будущий | ⬜ |
| Вышка (`CampWatchtowerBlock`) | Block | Будущий | ⬜ |
| Склад (`CampStorageBlock`) | Block | Будущий | ⬜ |

---

## Реализация

Фактический код — [`ModCreativeTabs.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModCreativeTabs.java).

### 1. Регистрация вкладки (Forge 1.20.1)

В 1.20.1 вкладка создаётся через `DeferredRegister<CreativeModeTab>` и `CreativeModeTab.builder()`.
Анонимный класс `new CreativeModeTab(...)` и метод `makeIcon()` относятся к 1.19 и **не применяются**.

```java
public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SurvivalPointsMod.MOD_ID);

public static final RegistryObject<CreativeModeTab> SURVIVALPOINTS_TAB =
        TABS.register("survivalpoints", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.survivalpoints"))
                .icon(() -> new ItemStack(ModBlocks.CAMP_RADIO_ITEM.get()))
                .displayItems((params, output) -> {
                    output.accept(ModBlocks.CAMP_RADIO_ITEM.get());
                    output.accept(ModEntities.BASE_GUARDIAN_SPAWN_EGG.get());
                    output.accept(ModEntities.BASE_SURVIVOR_SPAWN_EGG.get());
                })
                .build());
```

### 2. Регистрация предметов

Предметы **не** привязываются к вкладке через `Item.Properties().tab(...)` — этого API в 1.20.1 нет.
Состав вкладки задаётся в `displayItems(...)` при её создании.

### 3. Яйца призыва

Яйца создаются как `ForgeSpawnEggItem` и регистрируются в общем `ITEMS`-регистре
([`ModBlocks.ITEMS`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java)), после чего
добавляются в `displayItems(...)`.

---

## Файлы

- [`ModCreativeTabs.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModCreativeTabs.java) — регистрация вкладки и её содержимого
- [`ModBlocks.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModBlocks.java) — блоки, предметы блоков, `ITEMS`-регистр
- [`ModEntities.java`](../../../src/main/java/com/phian404/survivalpoints/init/ModEntities.java) — типы сущностей и яйца призыва
- [`SurvivalPointsMod.java`](../../../src/main/java/com/phian404/survivalpoints/SurvivalPointsMod.java) — вызов `ModCreativeTabs.register(...)`
