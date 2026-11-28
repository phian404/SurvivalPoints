# GUARDIAN_STATE_DECISION — решение развилки `GuardianState`

> **Статус: УТВЕРЖДЕНО владельцем (вариант A).** Документ разблокирует шаг R0.1 в
> [`GUARDIAN_REWORK_PLAN.md`](GUARDIAN_REWORK_PLAN.md). Код меняется по шагам §7 —
> каждый шаг проверяется в игре; коммит на границе шага.

Связанные документы:
- [`GUARDIAN_REWORK_PLAN.md`](GUARDIAN_REWORK_PLAN.md) — реворк стража, шаг R0.1 (потребитель решения);
- [`DESIGN_CAMP_SYSTEM.md`](../Camp/DESIGN_CAMP_SYSTEM.md) §5.1, §6, §14 — лагерные состояния (второй участник развилки);
- [`ROADMAP.md`](../Roadmap/ROADMAP.md) §2 — волна 3 «Стражи на постах»;
- [`AI_CONTEXT.md`](../../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md) §4 — состояние проекта.

---

## 1. Суть развилки

| Сторона | Предложение | Источник |
|---|---|---|
| **A** | Удалить боевой автомат `GuardianState`/`DATA_AI_STATE`/`updateAIState()`/`CombatState`/`getCombatState()`. Боевое поведение — прерогатива голов `GoalSelector`. | [`GUARDIAN_REWORK_PLAN.md:10`](GUARDIAN_REWORK_PLAN.md:10), R0.1 |
| **B** | Расширить `GuardianState` лагерными состояниями `ALERT`/`DEFEND`/посты/ночь. | [`DESIGN_CAMP_SYSTEM.md:125`](../Camp/DESIGN_CAMP_SYSTEM.md:125), §5.1 |

**Вывод:** противоречие искусственное. Лагерные состояния по самому же
[`DESIGN_CAMP_SYSTEM.md` §14](../Camp/DESIGN_CAMP_SYSTEM.md:446) планируются **отдельными
целями** (`GuardianPostGoal`, `GuardianPatrolGoal`, `GuardianFarmGoal`, `GuardianAlertGoal`,
`GuardianDefendGoal`), а ночь/тревога/привязка — это **данные лагеря**, а не состояние сущности.
Расширять enum для них не требуется.

---

## 2. Что такое `GuardianState` сейчас (по коду)

### 2.1 Определение

`public enum GuardianState { IDLE, RANGED, MELEE, PANIC }` —
[`BaseGuardianEntity.java:155`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:155).

- Хранится в `SynchedEntityData` как `DATA_AI_STATE` (BYTE) —
  [`BaseGuardianEntity.java:69`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:69),
  `define` в [`defineSynchedData()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:318).
- **В NBT не сохраняется** (в `addAdditionalSaveData`/`readAdditionalSaveData` отсутствует) —
  `GoalSelector` тоже не сериализуется.
- Пересчитывается **каждый серверный тик** в
  [`updateAIState()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:420),
  которая вызывается **после** `super.tick()` (то есть после тика голов) —
  [`tick()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:530).

### 2.2 Кто читает состояние

| Потребитель | Где | Что читает | Классификация |
|---|---|---|---|
| `GuardianMeleeAttackGoal` | [`:49`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:49), [`:62`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:62) | `== MELEE` | боевое — уходит в гол |
| `GuardianRangedAttackGoal` | [`:51`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRangedAttackGoal.java:51), [`:60`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRangedAttackGoal.java:60) | `== RANGED` | боевое — уходит в гол |
| `GuardianRetreatGoal` | [`:43`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRetreatGoal.java:43), [`:52`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRetreatGoal.java:52) | `== PANIC` | боевое — уходит в гол |
| `GuardianLookAtPlayerGoal` | [`:41`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianLookAtPlayerGoal.java:41), [`:47`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianLookAtPlayerGoal.java:47) | `== IDLE` | боевое/покой — предикат «не в бою» |
| `syncEquipmentToState()` | [`:482`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:482) | `== MELEE` → меч, иначе арбалет | производное — можно считать из активного гола |
| `updateRangedCombat()` (гейт стрельбы) | [`:727`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:727) | `RANGED`/`PANIC` | производное |
| `shouldFaceTarget()` | [`:562`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:562) | `RANGED`/`PANIC`/`MELEE` | производное |
| `applyFacing()` | [`:636`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:636) | `== MELEE` | производное |
| `tick()` — гашение наклона | [`:549`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:549) | `== IDLE` | производное |
| `swing()` — звук меча | [`:1441`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1441) | `getCombatState()` | производное |
| `doHurtTarget()` — звук удара | [`:1456`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1456) | `getCombatState()` | производное |
| `GuardianSnapshot` | [`:29`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianSnapshot.java:29), [`:50`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianSnapshot.java:50) | `getAIState()` (текст) | диагностика |
| `GuardDebugCommand` | [`:123`](../../../src/main/java/com/phian404/survivalpoints/init/GuardDebugCommand.java:123) | `getAIState()` (текст) | диагностика |
| `GuardianModel.setupAnim` | [`:248`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:248), [`:253`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:253), [`:268`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:268) | `getCombatState() == RANGED` | рендер |
| `GuardianRenderer.render` | [`:125`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:125), [`:138`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:138) | `!= IDLE`, `!= MELEE` | рендер |

### 2.3 Синхронизация на клиент

`DATA_AI_STATE` синхронизируется (это `SynchedEntityData`), и клиент реально его читает —
рендер. **`target` на клиент не синхронизируется вообще** (ванильно это серверное поле).

### 2.4 Проблемы текущего автомата

- **К3/К4:** состояние считается **после** тика голов ⇒ голы видят состояние прошлого тика
  (задержка 1 тик); это второй «мозг» рядом с `GoalSelector`.
- **1.1:** при `isEating()` автомат форсирует `IDLE`
  ([`:421`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:421)) ⇒
  боевые голы не вступают, оружие не выдаётся — «статуя с хлебом».
- Автомат держит решения, которые естественно живут в голе: гистерезис `MELEE↔RANGED`,
  приоритет `PANIC`, исключение для крипера, сброс рубежа
  ([`:441–469`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:441)).

---

## 3. Принятое решение (вариант **A**)

> **`GuardianState` как боевой автомат удаляется.** Боевое поведение — прерогатива голов
> (`GoalSelector`), лагерное состояние — данные лагеря (`CampRecord`/`CampPost` + `CampId`/`CampPostId`)
> плюс отдельные лагерные голы. `DATA_AI_STATE` сохраняется **только как серверный
> диагностический кэш**.

### 3.1 Судьба `DATA_AI_STATE`

| Аспект | Решение |
|---|---|
| Существование | Остаётся, но **только как диагностический кэш для `/sp_guard`** |
| Кто пишет | `BaseGuardianEntity` из **активного гола** (лёгкое вычисление производного состояния) |
| Кто читает логика | **Никто.** Голы решения на нём не строят |
| Синхронизация на клиент | **Не требуется** (см. §3.3) |
| `CombatState`/`getCombatState()`/`updateAIState()`/`setAIState()`/`isPanicking()` | Удаляются как API логики |

### 3.2 Куда уходят решения автомата

| Решение в `updateAIState()` | Новое место |
|---|---|
| Гистерезис `MELEE↔RANGED` (вход/выход по дистанции) | `canUse()`/`canContinueToUse()` меч-гола vs ranged-гола; ровно один боевой гол с `MOVE` (К2) |
| Приоритет `PANIC` (`HP < PANIC_HP_RATIO`) | Производный предикат паники; паникующий гол получает приоритет регистрации |
| Исключение для крипера (держать арбалет вблизи) | Гейт меч-гола (`target instanceof Creeper` → меч не вступает) |
| Сброс рубежа при `PANIC`/`IDLE` ([`BaseGuardianEntity:466`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:466)) | `stop()` соответствующего гола и/или смена цели (а не ежетиковая ветка автомата) |
| `syncEquipmentToState()` | Экипировка по активному боевому голу (меч/арбалет) с сохранением раннего выхода по `isEating()` |
| Гейт стрельбы `updateRangedCombat()` | Чистый предикат: `hasTarget && !meleeActive && !eating`; паника — производная от здоровья |
| `tick()` — гашение наклона в покое | Предикат «нет активного боевого гола» |

### 3.3 Рендер (клиент `GuardianState` **не читает вообще**)

Сейчас рендер зависит от состояния в двух местах:

1. **Поза арбалета** — [`setupAnim:248`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:248)
   читает `getCombatState() == RANGED`.
2. **Ориентация тела/головы** — [`render:125/138`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:125)
   читает `getAIState() != IDLE` / `!= MELEE` (лечит дефект «тело боком, хотя стреляет точно»).

**Новая схема:**

| Что | Источник на клиенте | Проверено |
|---|---|---|
| Поза арбалета | `MAINHAND is CROSSBOW` + `isCharging()` + `!isEating()` | предмет в руке доступен клиенту |
| «Меч vs арбалет» | ванильный `isAggressive()` | выставляется **только** в [`GuardianMeleeAttackGoal.start/stop`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:85); синхронизируется через `Mob.DATA_MOB_FLAGS_ID` |
| «Смотрит на цель» (тело/голова) | **новый** синхро-флаг `DATA_FACING_TARGET`, сервер заполняет из [`shouldFaceTarget()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:562) | — |

Проверки API через MCP `minecraft-modding` (1.20.1, mojang):
- `Mob.DATA_MOB_FLAGS_ID` — **существует**;
- `Mob.setAggressive(boolean)` / `isAggressive()` — **существуют**, состояние синхронизируется;
- `HumanoidModel.ArmPose.CROSSBOW_HOLD` — **существует**.

> `isAggressive()` в режиме ranged **false** (дальний бой флаг не трогает). Поэтому одного
> `isAggressive()` для ориентации тела/головы **недостаточно** — отсюда отдельный флаг
> `DATA_FACING_TARGET`.

---

## 4. Лагерные состояния волны 3 — где храним

**`GuardianState` лагерными состояниями НЕ расширяем.**

| Что | Где живёт |
|---|---|
| Поведение `POST`/`PATROL`/`FARM`/`ALERT`/`DEFEND` | **Отдельные голы** (`GuardianPostGoal`, `GuardianPatrolGoal`, `GuardianFarmGoal`, `GuardianAlertGoal`, `GuardianDefendGoal`) — [`DESIGN_CAMP_SYSTEM.md` §14](../Camp/DESIGN_CAMP_SYSTEM.md:446) |
| Ночь / закрытие / зона тревоги | `CampRecord.mode` (`night`, `closed`, `alertRadius`) — [`CampRecord.java:55`](../../../src/main/java/com/phian404/survivalpoints/camp/CampRecord.java:55) |
| Позиция/сектор/тип поста | `CampPost` (позиция, `facingYaw`, `Type`) — [`CampPost.java:37`](../../../src/main/java/com/phian404/survivalpoints/camp/CampPost.java:37) |
| Привязка страж↔лагерь/пост | `BaseSurvivorEntity.campId`/`campPostId` (`CampId`/`CampPostId` в NBT) — [`BaseSurvivorEntity.java:99`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseSurvivorEntity.java:99) |
| Режим ночи/тревоги, нужный голу | гол читает из `CampRecord` (штатный путь, лагерь тикается раз в 20 тиков, [`§13.4`](../Camp/DESIGN_CAMP_SYSTEM.md:412)); при необходимости — синхро-флаг для **рендера**, но не для логики |
| Привязка через identity | `BaseSurvivorEntity.getIdentityId()` — постоянный id, а не runtime UUID ([`§13.3`](../Camp/DESIGN_CAMP_SYSTEM.md:401)) |

Система лагерей **частично реализована**: в коде есть данные лагеря, радио-якорь, размещение структур, модульные ограждения и ворота, автоматический поиск площадок, а также фермерский менеджер и рабочая станция. Это не означает, что все лагерные поведения стражей из волны 3 уже реализованы. Перед изменением границ решения A сверять фактические голы и данные в исходниках; старое утверждение о полностью отсутствующей волне 2 больше неактуально.

---

## 5. Риски и митигации

| # | Риск | Влияние в игре | Митигация |
|---|---|---|---|
| **R1** | `isAggressive` не отражает ranged | тело/голова боком во время стрельбы (регресс уже исправленного дефекта) | синхро-флаг `DATA_FACING_TARGET` (§3.3) |
| **R2** | Поза арбалета от предмета в руке | нет регресса: во время еды в руке хлеб → поза сама «пустая» | проверить при приёмке |
| **R3** | Перенос гистерезиса `MELEE↔RANGED`, `PANIC`, крипера в голы | возврат «качелей» `приблизился—отошёл`, поздний вход в панику | явный контракт приоритетов + ровно один боевой гол с `MOVE` (К2) |
| **R4** | Сброс рубежа при `PANIC`/`IDLE` | рубеж залипает либо сбрасывается слишком часто | перенести сброс в `stop()` гола / на смену цели |
| **R5** | `syncEquipmentToState()` по активному голу | хлеб подменится арбалетом на середине еды | сохранить ранний выход по `isEating()` |
| **R6** | Гейт стрельбы по `RANGED/PANIC` | стрельба пропадёт/появится в покое | предикат `hasTarget && !meleeActive && !eating`; паника = производная от здоровья |
| **R7** | `PANIC` нужен `GuardianRetreatGoal` | отступление/зов союзников сломаются | паника — вычисляемый предикат, не enum |
| **R8** | `/sp_guard` печатает `state=` | отчёт опустеет | печатать производное состояние или серверный кэш |
| **R9** | NBT | нет — enum не сохранялся | — |
| **R10** | Совместимость R0.3 | меч-гол гейтится enum ([`:49`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:49)) — правится вместе с R0.1 | не ломать наследование `MeleeAttackGoal` и дефолтную анимацию |
| **R11** | `isAggressive` сбрасывается `WrappedGoal.stop()` (F4) | залипание маловероятно | — |
| **R12** | Производительность | снятие ежетикового автомата — плюс | — |

---

## 6. Совместимость с уже выполненными шагами

| Шаг | Статус | Как решение влияет |
|---|---|---|
| **R0.3** — ближний бой на ванильном `MeleeAttackGoal` | ✅ выполнен | Меч-гол сохраняет наследование и дефолтную анимацию; меняется **только** гейт `canUse()`/`canContinueToUse()` (с enum на живую цель/дистанцию). Кастомная анимация взмаха не возвращается |
| **R0.4** — еда: `eating` не сериализуется, сторож `eatPulse`, прерывание по цели и урону | ✅ выполнен | `GuardianEatBreadGoal` и сторож `eatPulse` **не трогаются**. Ранний выход `syncEquipmentToState()` по `isEating()` сохраняется (R5). Отложенный пункт R0.4 (`canUse()` = «рядом нет монстров») закрывается естественно при переходе на живую цель |
| **Этап 0** — рубеж в сущности (`standoffReached`/`standoffAnchor`) | ✅ выполнен | Рубеж остаётся полем сущности; меняется только **место сброса** (R4) |
| **R0.0 / R0.2** | ⚠️ частично | Не затрагиваются этим решением |

---

## 7. Порядок дальнейших шагов и оценка объёма

> Реализация — **отдельная команда владельца**. Ниже — порядок внутри R0.1, каждый пункт
> проверяется в игре.

| # | Шаг | Объём | Файлы |
|---|---|---|---|
| 1 | Живые предикаты в голах: убрать гейт по `getAIState()` у меч/ranged/retreat/look-at-player строятся на живой цели и дистанции | ~40–70 строк | `entity/ai/*.java` |
| 2 | Экипировка по активному боевому голу (+ ранний выход по `isEating()`) | ~20 строк | [`BaseGuardianEntity`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java) |
| 3 | Гейт стрельбы и паника — производные предикаты | ~20–30 строк | `BaseGuardianEntity` |
| 4 | Ориентация тела/головы: `shouldFaceTarget()` из активного гола; `applyFacing()`/`tick()` без enum | ~20 строк | `BaseGuardianEntity` |
| 5 | Удалить `GuardianState`/`CombatState`/`getCombatState()`/`updateAIState()`/`setAIState()`/`isPanicking()`; `DATA_AI_STATE` — серверный кэш из активного гола | ~30–60 строк (минус) | `BaseGuardianEntity` |
| 6 | Рендер: поза арбалета от предмета в руке + `isCharging()`; тело/голова по `DATA_FACING_TARGET`; `isAggressive()` только «меч vs арбалет» | ~30 строк | [`ModEntityRenderers`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java) |
| 7 | Диагностика: `/sp_guard` печатает производное состояние/кэш | ~10 строк | [`GuardianSnapshot`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianSnapshot.java), [`GuardDebugCommand`](../../../src/main/java/com/phian404/survivalpoints/init/GuardDebugCommand.java) |
| **Итого** | Комплексная оценка | **≈150–250 строк** | — |

**Проверка владельца:** в покое — гуляет/стоит; при появлении зомби — выхватывает арбалет и стреляет, тело/голова смотрят на цель; при подходе вплотную — меч с дефолтной анимацией; в панике — отход с арбалетом и зов союзников; «статуя с хлебом» не появляется.

---

## 8. Утверждённые решения владельца

| Вопрос | Решение |
|---|---|
| Развилка A/B/C | **A** — убрать боевой автомат; боевое — в голах, лагерное — данные + лагерные голы |
| `DATA_AI_STATE` | Остаётся **только серверным диагностическим кэшем**; логика его не читает |
| Рендер | Поза арбалета — от предмета в руке + `isCharging()` + `!isEating()`; тело/голова — новый `DATA_FACING_TARGET` (из `shouldFaceTarget()`); `isAggressive()` — только «меч vs арбалет»; клиент `GuardianState` не читает |
| Лагерные состояния волны 3 | Отдельные голы + данные `CampRecord`/`CampPost`/`CampId`/`CampPostId`; `GuardianState` лагерными **не расширяем** |
| Место записи решения | Этот файл + правка шапки [`GUARDIAN_REWORK_PLAN.md`](GUARDIAN_REWORK_PLAN.md:17) + абзац в `AI_CONTEXT.md` §4 |
