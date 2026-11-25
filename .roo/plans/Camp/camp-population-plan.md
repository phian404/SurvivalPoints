# План: население независимого лагеря при генерации

> Статус: спроектировано, ожидает перехода в `code`.
> Источник истины — исходный код; документы `.roo/plans/` используются только как контекст.
> Ограничение: не создавать системы торговли, диалогов, заданий, репутации; не переписывать систему NPC.

## 1. Цель

При генерации лагеря создавать 4–5 NPC:

| Кто | Роль (`SurvivorRole`) | Профессия (`SurvivorProfession`) | Связь |
|---|---|---|---|
| Лидер | `CIVILIAN` | `NONE` | `CampRecord.leaderIdentityId` |
| Фермер | `CIVILIAN` | `FARMER` | `workPoiPos` → POI `farm_plot` |
| Страж | `GUARDIAN` | `NONE` | `campPostId` → свободный `CampPost` |
| Выживший ×1–2 | `CIVILIAN` | `NONE` | только `campId` |

Разделение понятий (зафиксировано владельцем):

- `SurvivorRole` (`CIVILIAN`/`GUARDIAN`) — боевая роль, остаётся отдельным перечислением.
- `SurvivorProfession` (`NONE`/`FARMER`) — новое перечисление, не объединяется с ролью, лидерством или назначением.
- Лидерство — только в `CampRecord.leaderIdentityId`; в NPC поля лидерства нет.
- Назначение стража — существующий `campPostId`; назначение фермера — новый `workPoiPos`.
- Универсальная система назначений не создаётся.

Восстановление населения при загрузке мира **не добавляется**. Живые NPC сохраняются штатными средствами Minecraft (сущности с `persistenceRequired`), погибшие не заменяются автоматически.

## 2. Факты из кода, на которых основан план

- [`CampManager.createCamp()`](../../src/main/java/com/phian404/survivalpoints/camp/CampManager.java:91) — единственная точка создания лагеря, две перегрузки (ручная и из готового STRICT-плана). Порядок: `prepareTerrain` → `new CampRecord` → `addCamp` → `CampGenerator.generate` → `CampGenerator.createPostsFromPois` → `setDirty`.
- Вызовы `createCamp`: [`CampRadioBlock.onPlace()`](../../src/main/java/com/phian404/survivalpoints/camp/block/CampRadioBlock.java:65), [`CampDebugCommand.generate()/create()`](../../src/main/java/com/phian404/survivalpoints/init/CampDebugCommand.java:207), [`CampSiteDirector.scanLevel()`](../../src/main/java/com/phian404/survivalpoints/camp/site/CampSiteDirector.java:370).
- [`CampGenerator.generate()`](../../src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java:1301) ставит периметр, модули, дорожки, радио; заполняет `anchor`, `center`, `gatePos`. POI фермы (`survivalpoints:poi/farm_plot`) уже собраны внутри `CampStructurePlacer.placeModule()`.
- [`CampGenerator.createPostsFromPois()`](../../src/main/java/com/phian404/survivalpoints/camp/CampGenerator.java:1977) создаёт GATE-пост у ворот и TOWER/WALL-посты из маркеров; `farm_plot` исключён. Посты есть только после этого вызова.
- [`RoleAssignmentService.create()`](../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:51) уже создаёт NPC нужной роли, задаёт `identityId`, `campId`, `campPostId`, скин, рост, имя. Не делает: `moveTo`, `setPersistenceRequired`, `addFreshEntity`, экипировку стража (есть отдельный `prepareGuardianEquipment()`).
- [`RoleAssignmentService.copySharedState()`](../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:131) переносит общие данные при смене роли через замену сущности.
- [`BaseSurvivorEntity`](../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorEntity.java:32) уже хранит в NBT `IdentityId`, `Role`, `Skin`, `Gender`, `Scale`, `CampId`, `CampPostId`.
- [`CampRecord`](../../src/main/java/com/phian404/survivalpoints/camp/CampRecord.java:26) хранит `posts`, `guardianPosts`, `moduleSlots`, `pois`, ресурсы и режим; полей жителей и лидера нет. NBT — `save()`/`load()`.
- [`CampPoi`](../../src/main/java/com/phian404/survivalpoints/camp/CampPoi.java:20) — `slotId`, `type`, `pos`. Тип пашни — [`CampStructurePlacer.FARM_PLOT_TYPE`](../../src/main/java/com/phian404/survivalpoints/camp/CampStructurePlacer.java:51).
- [`CampSitePlanner.Plan`](../../src/main/java/com/phian404/survivalpoints/camp/CampSitePlanner.java:211) даёт `localToWorld(lx, lz, y)`, `getFloorY()`, `getGatePos()`, `getRadioPos()`, `getFarmPos()`, `getWatchtowerPos()`, `getGateFacing()`. Двор компактного лагеря 21×21 (`radius = 10`, локальные клетки `0..20`), вышка 5×8 в углу, ферма 9×9, дорожки шириной 2.
- Обработчика смерти NPC сейчас нет; `CampRecord.releaseGuardian()` существует, но нигде не вызывается.

## 3. Момент создания NPC

В обеих перегрузках `CampManager.createCamp()`:

```
prepareTerrain → new CampRecord → addCamp → CampGenerator.generate
→ CampGenerator.createPostsFromPois → CampPopulation.spawn → setDirty
```

Обоснование:

- после `generate` стоят все постройки, дорожки и радио, заполнены геометрические поля `record`, POI фермы уже в `record.getPois()`;
- после `createPostsFromPois` существуют посты, к которым можно привязать стража;
- до `setDirty` всё попадает в одну транзакцию сохранения;
- при отказе участка (`plan`/`checkPreparationZone`) NPC не создаются вообще.

`CampPopulation.spawn()` вызывается только из `createCamp()`. Ни тик лагеря, ни загрузка мира NPC не создают.

## 4. Безопасные позиции появления

Все точки считаются в локальной сетке плана и переводятся в мир через `plan.localToWorld(lx, lz, plan.getFloorY())`. Для компактного лагеря `r = 10`, `side = 21`:

| Житель | Локальная клетка | Обоснование |
|---|---|---|
| Лидер | `(r-2, r+2)` = `(8, 12)` | рядом с радио `(10, 12)`, вне дорожки (`x = 9..10`) |
| Фермер | `(2r-7, 10)` = `(13, 10)` | у входа фермы, снаружи габарита `x = 11..19`, `z = 1..9` |
| Страж | `(r, 2r-1)` = `(10, 19)` | внутри у ворот; GATE-пост снаружи — это назначение, не позиция |
| Выживший 1 | `(r-3, r-3)` = `(7, 7)` | свободный двор вдали от радио и дорожек |
| Выживший 2 | `(r-2, r+5)` = `(8, 15)` | свободный двор ближе к югу |

Проверка кандидата перед спавном:

1. блок на `floorY` и `floorY + 1` проходим (не `blocksMotion()`, без жидкости);
2. блок на `floorY - 1` твёрдый (опора под ногами);
3. точка не внутри `CampModuleSlot.getBounds()` ни одного слота;
4. точка не совпадает с POI и не внутри габарита вышки/фермы;
5. расстояние до уже размещённых NPC ≥ 2 блоков.

При занятости — обход спиралью радиуса 2–3 от базовой клетки с той же проверкой. Если все кандидаты заняты — житель не создаётся (см. §7). Позиция спавна и `workPoiPos` фермера — разные величины: фермер стоит у входа, работает на грядке.

## 5. Фактическое добавление NPC в мир

`CampPopulation.spawn()` для каждого жителя:

1. `RoleAssignmentService.create(level, role, record.getId(), postId)` — создаёт сущность с `identityId`, `campId`, `campPostId`, скином, ростом, именем.
2. Установить `profession` и `workPoiPos` (для фермера — из POI, см. §6).
3. `entity.moveTo(x + 0.5, floorY, z + 0.5, yaw, 0)` — в проверенную позицию.
4. `entity.setPersistenceRequired()` — чтобы пережить выгрузку чанков и перезаход.
5. Для GUARDIAN — `RoleAssignmentService.prepareGuardianEquipment(entity)`.
6. `serverLevel.addFreshEntity(entity)` — **фактическое появление в мире**.
7. Только при `true`: `record.addResident(entity.getIdentityId())`; для стража — `record.assignGuardian(identityId, post)`; для лидера — `record.setLeaderIdentityId(identityId)`.

`addFreshEntity` возвращает `false`, если UUID занят или сущность помечена удалённой. В этом случае сущность не считается жителем.

## 6. Назначение фермерской POI

- Искать первый POI с типом `CampStructurePlacer.FARM_PLOT_TYPE` в `record.getPois()`.
- Найденную позицию записать через `entity.setWorkPoiPos(poi.getPos())` до `addFreshEntity`.
- Если подходящей POI нет — `workPoiPos` остаётся `null`; фермер создаётся без рабочей точки. Фиктивную позицию (`plan.getFarmPos()`, центр лагеря) подставлять запрещено.
- `workPoiPos` сохраняется в NBT и переносится при смене роли через `copySharedState()`.
- Универсальная система назначений не создаётся: `campPostId` и `workPoiPos` — два конкретных поля для двух случаев.

## 7. Частичное создание населения

- Каждый житель добавляется в `residents` только после успешного `addFreshEntity`. Частичный успех допустим: лагерь может получить 3 жителей вместо 5.
- Если не нашлась безопасная позиция — житель пропускается, остальные создаются.
- Если не нашёлся свободный пост для стража — страж не создаётся; `campPostId` не выдумывается.
- Если `addFreshEntity` вернул `false` — сущность не добавляется в `residents`, пост не занимается, лидер не назначается.
- При отказе лидера (первого жителя) `leaderIdentityId` остаётся `null`; лидером становится первый **успешно созданный** житель, если он задуман как лидер. Если лидер не создан совсем — лагерь остаётся без лидера, но с жителями.
- Откат уже созданных сущностей не производится: они реально exist в мире и сохранены. Частичный состав — допустимое состояние, не ошибка.

## 8. Момент установки `populationInitialized`

Флаг `populationInitialized` в `CampRecord` — защита от дубликатов. Правила:

1. Значение по умолчанию для нового лагеря — `false`.
2. `spawn()` проверяет флаг в самом начале: если `true` — выход без создания.
3. Флаг выставляется **только после завершения цикла создания всех жителей**, включая успешные и пропущенные, и только если цикл дошёл до конца без исключения.
4. Флаг выставляется даже при частичном успехе (3 из 5) — это предотвращает повторный спавн и дубликатов. Неудачная генерация не фиксируется как «завершённая» только в одном случае: если сам вызов `spawn()` не дошёл до конца (исключение/отказ до цикла) — флаг остаётся `false`.
5. Флаг сохраняется в NBT. Старые лагеря без тега загружаются как `false`, но автоматического спавна при загрузке нет (см. §1) — повторный спавн возможен только через явный вызов из `createCamp()` или отладочную команду.
6. Повторный вызов `createCamp()` для того же лагеря невозможен: запись добавляется в `CampSavedData` один раз, а якорь-радио при повторной установке распознаётся как ретранслятор.

Итог: дубликаты исключены флагом; неудачная генерация не фиксируется как завершённая, пока цикл создания не завершён.

## 9. Согласованность `CampRecord.residents` с реальными NPC

- Источник истины — `CampRecord.residents` (список `identityId`).
- Запись — только после успешного `addFreshEntity` (§5).
- `leaderIdentityId` — один из `residents`, сбрасывается при гибели лидера.
- Гибель обрабатывается через новый обработчик `LivingDeathEvent` в [`ModEntityEvents`](../../src/main/java/com/phian404/survivalpoints/init/ModEntityEvents.java:38):
  - если погибший — `BaseSurvivorEntity` с `campId`, найти лагерь через `CampManager.getCamp()`;
  - `record.removeResident(identityId)`;
  - `record.releaseGuardian(identityId)` — освободить `campPostId`;
  - если `identityId.equals(record.getLeaderIdentityId())` — сбросить лидера;
  - `CampManager.data(level).setDirty()`.
- Смена роли через `RoleAssignmentService.reassign()` сохраняет `identityId`, поэтому `residents` остаётся валидным; `profession` и `workPoiPos` переносятся в `copySharedState()`.
- Восстановление погибших не производится: список жителей только уменьшается.

## 10. Конкретные изменения по файлам

| Файл | Изменение |
|---|---|
| `entity/role/SurvivorProfession.java` (новый) | enum `NONE`, `FARMER` + безопасный `byName` по образцу `SurvivorRole` |
| [`BaseSurvivorEntity`](../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorEntity.java:32) | поля `profession`, `workPoiPos`; геттеры/сеттеры; NBT `Profession`, `WorkPoiPos` в `addAdditionalSaveData`/`readAdditionalSaveData` |
| [`RoleAssignmentService.copySharedState()`](../../src/main/java/com/phian404/survivalpoints/entity/role/RoleAssignmentService.java:131) | перенос `profession` и `workPoiPos` |
| [`CampRecord`](../../src/main/java/com/phian404/survivalpoints/camp/CampRecord.java:26) | `residents`, `leaderIdentityId`, `populationInitialized`; NBT; методы `addResident`, `removeResident`, `setLeaderIdentityId`, `clearLeaderIf`, `isPopulationInitialized`, `setPopulationInitialized` |
| `camp/CampPopulation.java` (новый) | `spawn(ServerLevel, CampRecord, CampSitePlanner.Plan)`: выбор позиций, создание 4–5 NPC, привязка поста и POI, откат, установка флага |
| [`CampManager.createCamp()`](../../src/main/java/com/phian404/survivalpoints/camp/CampManager.java:91) | две перегрузки: вызвать `CampPopulation.spawn()` после `createPostsFromPois`, до `setDirty` |
| [`ModEntityEvents`](../../src/main/java/com/phian404/survivalpoints/init/ModEntityEvents.java:38) | обработчик `LivingDeathEvent` по §9 |
| [`CampDebugCommand.info()`](../../src/main/java/com/phian404/survivalpoints/init/CampDebugCommand.java:154) | вывод `residents`, `leaderIdentityId`, `populationInitialized` |

Не трогаются: `SurvivorRole`, регистрация сущностей, рендеры, `CampGenerator` (кроме уже существующего `createPostsFromPois`), системы торговли/диалогов/заданий/репутации.

## 11. Потенциальные проблемы

- `addFreshEntity` может отказать — обрабатывается частичным составом (§7).
- NPC может появиться внутри изменённого игроком модуля — проверка позиций (§4) по `moduleSlots` и POI.
- `guardianPosts` мог содержать устаревшие записи — `releaseGuardian` при гибели (§9).
- Ночью ворота закрыты — страж с GATE-постом снаружи может оказаться отрезан; на этом этапе не исправляется, фиксируется как известное ограничение.
- Удаление якоря удаляет `CampRecord`, но не NPC — NPC остаются в мире; на этом этапе не исправляется.
- Отсутствие POI `farm_plot` — фермер без `workPoiPos` (§6), не ошибка.

## 12. Порядок игровых проверок

1. `/sp_camp generate <name>` на ровном участке → лагерь + 4–5 NPC, `/sp_camp info` показывает жителей и лидера.
2. Проверить, что лидер — обычный `CIVILIAN`, фермер — `FARMER` с `workPoiPos`, страж — `GUARDIAN` с `campPostId` на свободном посту.
3. Проверить позиции: NPC не внутри стен, фермы, вышки, дорожек; все на уровне пола.
4. Перезаход в мир → NPC на месте, состав и лидер сохранились, повторного спавна нет.
5. Смена роли фермера арбалетом → `identityId`, `profession`, `workPoiPos` сохраняются.
6. Убить жителя, стража, лидера по отдельности → `residents` уменьшается, пост освобождается, лидер сбрасывается, замены нет.
7. Автогенерация на фронтире (`CampSiteDirector`) → население создаётся так же, как при ручной генерации.
8. Лагерь без POI `farm_plot` (удалить грядки до генерации) → фермер без фиктивной позиции, лагерь не падает.
9. Повторная установка радио рядом → ретранслятор, нового населения не появляется.
