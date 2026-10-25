# Подэтап 2.1 — рабочий блок camp_radio

Цель: довести `CampRadioBlock` и `CampRadioBlockEntity` до рабочего состояния без генерации лагеря и без остальных блоков волны 2.

## Изменяемые файлы

- `src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java`
- `src/main/java/com/phian404/survivalpoints/camp/blockentity/CampRadioBlockEntity.java`
- `src/main/java/com/phian404/survivalpoints/init/ModBlocks.java`

## Шаги

1. `CampRadioBlock` перевести на `BaseEntityBlock` (уже сделано).
2. Добавить override `newBlockEntity(BlockPos, BlockState)` → `new CampRadioBlockEntity(pos, state)`.
3. В `getTicker(...)` добавить проверку `type == ModBlocks.CAMP_RADIO_BE.get()` (иначе `null`).
4. Исправить конструктор `CampRadioBlockEntity`: `(BlockPos, BlockState)` вызывает `super(ModBlocks.CAMP_RADIO_BE.get(), pos, state)`; удалить no-arg конструктор.
5. Добавить import `CampRadioBlock.POWERED` в `CampRadioBlockEntity`; `isPowered()` с null-проверкой (возвращать `false` при `null`).
6. Сохранять и загружать `campId` и `charge` (уже реализовано в `saveAdditional`/`load`).
7. В `tick(...)` обновлять `POWERED` по дню и ночи (уже реализовано).
8. Проверить регистрацию в `ModBlocks`: заменить фабрику-класс на лямбду `CampRadioBlockEntity::new`.
9. ПКМ показывает привязанный лагерь (уже реализовано через `CampManager.findCampAt`).
10. Автоматическое создание и распад лагеря не делать.

## Проверка

- `gradlew.bat compileJava` без ошибок.
- Установить блок в мире, перезапустить мир, убедиться, что BE сохраняется и блок не крашит игру.