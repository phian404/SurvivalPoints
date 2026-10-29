# GUARDIAN_REWORK_PLAN — реворк стража

Статус: **план, частично выполнен.** Сверено с рабочим деревом; шаги ниже отмечены по факту кода,
а не по намерению.

| Шаг | Статус | Подтверждение в коде |
|---|---|---|
| Этап 0 — рубеж в сущности | ✅ выполнен | `standoffReached`/`standoffAnchor` — поля сущности, сохраняются в NBT |
| R0.0 — `requiresUpdateEveryTick` у боевых голов | ⚠️ частично | переопределён у [`GuardianMeleeAttackGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:108) и [`GuardianEatBreadGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:120); у `GuardianRangedAttackGoal` и `GuardianRetreatGoal` — **нет** |
| R0.1 — удалить автомат состояний | ⬜ не начат, **разблокирован** | `GuardianState`, `DATA_AI_STATE`, `CombatState`, `updateAIState()` присутствуют и используются. Развилка решена владельцем (вариант A) — [`GUARDIAN_STATE_DECISION.md`](GUARDIAN_STATE_DECISION.md) |
| R0.2 — движение на ванили | ⚠️ частично | `GuardianMoveControl.strafeWithSpeed` есть и применяется; `moveDirectly`/`setDeltaMovement` остались под флагом `CUSTOM_MOVE` |
| R0.3 — ближний бой на ванильном `MeleeAttackGoal` | ✅ выполнен | [`GuardianMeleeAttackGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:31) наследует ванильный гол; кастомная анимация взмаха удалена |
| R0.4 — еда: уступка угрозе + watchdog | ⚠️ частично | Сделано: флаг `eating` не сериализуется; watchdog-пульс (`EAT_PULSE_TIMEOUT_TICKS`) в [`tickCountdowns()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:648); `canContinueToUse()` рвёт еду по цели **и** по `damageStunTicks > 0` ([`GuardianEatBreadGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:106)). Осталось: `canUse()` проверяет только `getTarget() == null` (скана «монстр рядом» нет); ранний выход `syncEquipmentToState()` по `isEating()` сохранён — оба пункта ждут R0.1 (см. R0.4) |
| R0.5 — порядок голов, `WaterAvoidingRandomStrollGoal` | ⬜ не начат | используется `RandomStrollGoal` |
| R0.6 — замеры и приёмка | ⬜ не начат | — |

> **Открытый вопрос — РЕШЁН владельцем (вариант A), см. [`GUARDIAN_STATE_DECISION.md`](GUARDIAN_STATE_DECISION.md).**
> R0.1 предлагал удалить `GuardianState`, а [`DESIGN_CAMP_SYSTEM.md`](../Camp/DESIGN_CAMP_SYSTEM.md) §5.1 —
> расширить его лагерными состояниями. Принято: **боевой автомат удаляется**, боевое поведение — прерогатива
> голов (`GoalSelector`), лагерное состояние — данные лагеря (`CampRecord`/`CampPost`/`CampId`/`CampPostId`)
> плюс отдельные лагерные голы; `GuardianState` лагерными состояниями **не расширяем**. `DATA_AI_STATE`
> остаётся только серверным диагностическим кэшем для `/sp_guard`; клиент его не читает. Решения автомата
> (гистерезис `MELEE↔RANGED`, `PANIC`, крипер, сброс рубежа) переходят в голы. R0.1 разблокирован —
> реализация по шагам §7 [`GUARDIAN_STATE_DECISION.md`](GUARDIAN_STATE_DECISION.md).

Задача (от владельца, 6 пунктов):
1. Страж стоит с хлебом и терпит урон, оружие не применяет.
2. Иногда страж зависает в воздухе (редко).
3. Ближний бой — пересмотреть полностью, не изобретать колесо, хочется обычную логику драки с дефолтными анимациями.
4. Исключить конфликты между приоритетами и их вызовами.
5. Не забывать про оптимизацию и рациональность.
6. Обязательно сверяться с MCP minecraft-modding, не забивать моба лишним кодом.

---

## 0. Факты, проверенные через MCP (не догадки)

| # | Факт | Источник |
|---|------|----------|
| F1 | `Mob.registerGoals()` **пуст**. `PathfinderMob` целей не регистрирует. `Monster` регистрирует, но мы не его наследник. | `Mob`, `PathfinderMob`, `Monster` |
| F2 | `GoalSelector.availableGoals` — это `LinkedHashSet`. Значит **порядок обхода целей = порядок регистрации**, а `priority` влияет ТОЛЬКО через `WrappedGoal.canBeReplacedBy()` (сравнение чисел). | `GoalSelector`, `WrappedGoal` |
| F3 | `GoalSelector.tick()`: фаза cleanup → `stop()` всем, у кого `canContinueToUse()==false`; фаза update → **первый подходящий по порядку регистрации** гол забирает свои флаги, вытесняя предыдущего (`prev.stop()`). | `GoalSelector` |
| F4 | `WrappedGoal.stop()` вызывает `goal.stop()` **только если `isRunning == true`**. `start()` всегда ставит `isRunning = true` перед `goal.start()`. | `WrappedGoal` |
| F5 | Ванильный `MeleeAttackGoal(PathfinderMob, double, boolean)`: `canUse()` — раз в 20 тиков; `createPath(target, 0)`; `start()` → `moveTo(path, speed)` + `setAggressive(true)`; `tick()` → `getLookControl().setLookAt(target, 30, 30)`, путь пересчитывается раз в `4 + random.nextInt(7)` тиков; `checkAndPerformAttack()` → `mob.swing(MAIN_HAND)` + `mob.doHurtTarget(target)`; `attackInterval = 20`. | `MeleeAttackGoal` |
| F6 | Ванильный `MoveControl.tick()` прыгает (`getJumpControl().jump()`) в двух случаях: (а) цель пути выше `maxUpStep()`; (б) **моб находится ВНУТРИ блока с коллизией** (`!shape.isEmpty() && mob.getY() < shape.max(Y) + pos.getY()`). После прыжка операция = `JUMPING` и снимается только при `onGround()`. | `MoveControl` |
| F7 | Ванильный `MoveControl.strafe(strafeForwards, strafeRight)` двигает моба **в локальной системе координат моба**: `strafe(-1, 0)` = шаг НАЗАД относительно курса, т.е. **лицом к врагу и назад** — ровно наш «отход». Плюс `isWalkable()` не даёт влезть в стену. | `MoveControl` |
| F8 | `Mob.doHurtTarget()`: knockback `Mth.sin(yRot)`, `-Mth.cos(yRot)`, затем `setDeltaMovement(multiply(0.6, 1.0, 0.6))`. | `Mob` |
| F9 | `Mob.setAggressive/isAggressive`, `getMeleeAttackRangeSqr`, `isWithinMeleeAttackRange` — готовые хелперы для ближнего боя. | `Mob` |
| F10 | **`Mob.serverAiStep()` тикает цели только КАЖДЫЙ ВТОРОЙ тик**: на чётных — полный `targetSelector.tick()` + `goalSelector.tick()`, на нечётных — `tickRunningGoals(false)`, который тикает **только** голы с `requiresUpdateEveryTick() == true`. Наши голы этого не переопределяют ⇒ **работают на 10 Гц, а не на 20 Гц**. Ванильный `MeleeAttackGoal` переопределяет `requiresUpdateEveryTick()` → `true`. | `Mob`, `MeleeAttackGoal` |
| F11 | Порядок внутри тика: `aiStep()` → `serverAiStep()` (цели → `navigation.tick()` → `moveControl.tick()` → `lookControl.tick()` → `jumpControl.tick()`) → прыжок → `xxa *= 0.98; zza *= 0.98;` → `travel()` → `moveRelative(getSpeed(), input)` → `move(SELF, delta)`. **⇒ Вход движения (`zza`/`xxa`) и скорость (`setSpeed`) применяются В ТОМ ЖЕ тике, в котором их задал гол.** | `LivingEntity`, `Mob` |
| F12 | `MoveControl.tick()` в состоянии WAIT делает `mob.setZza(0.0F)` — и вызывается он **после** `goalSelector.tick()`. **⇒ Собственный `setZza()` из гола будет обнулён.** Рабочий путь — `MoveControl.strafe(forwards, right)`: он ставит `operation = STRAFE`, и `MoveControl.tick()` сам выставит `setZza`/`setXxa`/`setSpeed` уже после нашего гола. | `MoveControl` |
| F13 | `MoveControl.strafe()` жёстко ставит `speedModifier = 0.25` ⇒ фактическая скорость = `0.25 × Attributes.MOVEMENT_SPEED`. Скорость можно менять только через `speedModifier` (protected) ⇒ нужен свой подкласс `MoveControl`. | `MoveControl` |
| F14 | `LivingEntity.travel()` → `moveRelative(this.getSpeed(), input)`; трение, гравитация и отброс — ванильные, **если не перезаписывать `setDeltaMovement`**. | `LivingEntity` |

**Главный вывод из F1:** ванильных целей у стража **ноль** — все 7 целей наши. Значит «конфликты приоритетов» — это конфликты **между нашими голами** и **между нашими голами и `GoalSelector`**.

**Главный вывод из F10:** **ни один наш гол не тикает каждый тик** — вся логика движения, стрельбы и еды работает на 10 Гц. Это отдельная и очень вероятная причина «резкости» (пункт владельца про «слишком резкие и быстрые»), «стоит перед зомби и стоит так секунду» и задержек в 1–2 тика. Лечится одной строкой на гол.

---

## 1. Диагноз

### Пункт 1 — «стоит с хлебом и терпит урон»

Причина — **не одна, а три сцепленные** (см. [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java) и [`GuardianEatBreadGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java)):

**1.1. Автомат состояний имеет приоритет над боевыми голами.**
[`updateAIState()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:404) начинается с раннего выхода:

```java
if (this.isEating()) {
    this.setAIState(GuardianState.IDLE);
    return;                      // ← цель полностью игнорируется
}
```

а [`syncEquipmentToState()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:427) — тоже с ранним выходом:

```java
if (this.isEating()) {
    return;                      // ← оружие не выдаётся, в руке остаётся хлеб
}
```

Пока `eating == true`:
- состояние принудительно `IDLE` ⇒ `GuardianRangedAttackGoal.canUse()` (проверяет `getAIState() == RANGED`) → **false**;
- `GuardianMeleeAttackGoal.canUse()` → **false**;
- оружие не синхронизируется ⇒ **хлеб в руке**.

**1.2. Гол еды уступает только цели, а цель появляется не сразу.**
[`GuardianEatBreadGoal.canUse()`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:36) требует `getTarget() == null` **и** `!isEating()` **и** ХП < 70%. Цель ставится:
- мгновенно — в [`ModEntityEvents.onLivingHurt()`](../../../src/main/java/com/phian404/survivalpoints/init/ModEntityEvents.java:88), но **только если** `event.getSource().getEntity() instanceof LivingEntity`. От падения, огня, лавы, кактуса, удушья, звезды голода, взрыва TNT — **источника-существа нет ⇒ цель не ставится**;
- с задержкой — [`GuardianTargetGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianTargetGoal.java:54) сканирует **раз в 20 тиков** и **только `instanceof Monster`**.

Пока цель не появилась — страж ест (1–2 с), стоит как вкопанный и получает урон. Это и есть наблюдаемое поведение.

**1.3. Флаг `eating` не имеет watchdog'а и переживает перезагрузку мира (латентный баг).**

*Общий механизм.* `setEating(false)` вызывается **только** в `stop()` гола. По F4 `stop()` вызывается, только если гол `isRunning`. Если сущность перестала тикать между `start()` и cleanup-фазой, `stop()` не придёт, а `GuardianEatBreadGoal.canUse()` требует `!isEating()` ⇒ **гол больше никогда не запустится ⇒ `stop()` больше никогда не вызовется ⇒ `eating` останется `true` навсегда**. Состояние навсегда `IDLE`, оружие навсегда не выдаётся, цель игнорируется. Это **ровно** «стоит с хлебом как вкопанный».

*Сценарий «вышел из мира во время еды» (подтверждён кодом; наблюдался владельцем).* Здесь работает второй слой — **сериализация**:

1. `eating` — поле сущности; пишется в NBT в [`addAdditionalSaveData()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1448) (`tag.putBoolean("Eating", ...)`) и читается в [`readAdditionalSaveData()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1476).
2. Таймеры гола `eatTimer`/`duration` живут **только в экземпляре гола** и **не сохраняются**. `GoalSelector` — не сериализуемое состояние: после загрузки голы пересоздаются «холодными».
3. Выход из мира = выгрузка сущности: `stop()` гола **не вызывается** (гола никто не останавливал), значит `eating = true` уходит в сейв.
4. При загрузке `eating = true` восстанавливается, но **активного гола еды нет** и `stop()` для него уже не случится. Watchdog'а нет ⇒ переходим в состояние «навсегда».

*Итоговая цепочка отказа — почему внешне «застывает с хлебом и не реагирует на врагов»:*

- [`GuardianEatBreadGoal.canUse()`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:43) требует `!isEating()` ⇒ гол еды мёртв **навсегда**;
- [`updateAIState()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:405) при `isEating()` каждый тик форсирует `GuardianState.IDLE`;
- [`GuardianRangedAttackGoal.canUse()`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRangedAttackGoal.java:51) и [`GuardianMeleeAttackGoal.canUse()`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:49) требуют `RANGED`/`MELEE` ⇒ **в бой не вступают**;
- [`syncEquipmentToState()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:461) выходит рано по `isEating()` ⇒ оружие не выдаётся, **в руке остаётся хлеб**;
- [`GuardianModel.setupAnim()`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java:279) при `currentEating` ставит руки в нейтральную позу ⇒ хлеб визуально в руке;
- `updateAIState()` из-за раннего выхода не доходит до `resetStandoff()` — рубеж/цель остаются в несогласованном состоянии.

*Смежное.* [`onLivingHurt()`](../../../src/main/java/com/phian404/survivalpoints/init/ModEntityEvents.java:88) ставит цель только при источнике-существе; при прочих источниках урона еда не прерывается — см. 1.2.

**Варианты фикса (код не трогаем до решения владельца)**

| # | Вариант | Что делаем | Объём | Плюсы | Минусы |
|---|---------|-----------|-------|-------|--------|
| A | **Минимальный** | (1) не восстанавливать `eating` из NBT (или восстанавливать как «гол еды не активен»); (2) watchdog в [`BaseGuardianEntity.tick()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:483): если `isEating()`, но `GuardianEatBreadGoal` не в `goalSelector.getRunningGoals()` — `setEating(false)` + `syncEquipmentToState()`; (3) прерывать еду при уроне и при появлении цели | ~15–25 строк | Точечно закрывает и «вышел из мира», и «залипший флаг»; не затрагивает автомат состояний | Оставляет 1.1/1.2/1.4 (еда vs угроза, задержка в 1 тик) |
| B | **В рамках R0.4** (штатный) | Всё из A + гол еды уступает угрозе: `canContinueToUse()` прерывается при `damageStunTicks > 0` или появлении цели; watchdog по таймауту `duration + 40` | ~40–60 строк | Лечит пункт 1 владельца целиком | Опирается на активный гол еды; зависит от F10 (`requiresUpdateEveryTick`) |
| C | **Радикальный (R0.1)** | Убрать `GuardianState`/`DATA_AI_STATE`/`updateAIState()`; `eating` сделать **производным** от наличия активного гола еды (или убрать поле) | ~150–250 строк | Закрывает 1.1–1.4 и К3/К4 разом; убирает второй «мозг» | Конфликт с [`DESIGN_CAMP_SYSTEM.md`](../Camp/DESIGN_CAMP_SYSTEM.md) §5.1 (лагерные состояния); переписывает рендер позы арбалета и `/sp_guard` |

Порядок: **A** (если владелец хочет снять симптом сейчас) → **B** в рамках R0.4 → решение по **C** после снятия открытого вопроса в шапке плана.

**Проверка владельца (для A/B):** ранить стража, дать ему поесть, выйти из мира и вернуться — страж не должен стоять с хлебом: хлеб уходит, в руке арбалет, зомби рядом снова получает отпор.

**1.4. Порядок вызовов добавляет задержку в 1 тик.**
`updateAIState()` работает **после** `super.tick()` (то есть после тика голов). В тик, когда цель появилась, боевой гол ещё видит старое состояние `IDLE` и не стартует; состояние обновится только в следующем тике.

**Вывод:** пункты 1.1–1.4 лечатся **устранением отдельного автомата состояний** (см. R0.1) — тогда боевые голы сами решают, кто главный, а гол еды физически не может «забрать» бой.

### Пункт 1.5 — общая причина «резкости» и задержек: голы тикают 10 раз в секунду

По F10 `goalSelector.tick()` запускается **каждый второй тик**, а `tickRunningGoals(false)` тикает только голы с `requiresUpdateEveryTick() == true`. **Ни один наш гол этого не переопределяет** ⇒ вся логика обновляется на **10 Гц вместо 20 Гц**:

- `GuardianRangedAttackGoal.tick()`, `GuardianMeleeAttackGoal.tick()`, `GuardianRetreatGoal.tick()` обновляют движение 10 раз в секунду ⇒ **рывками** — это и есть «резкие и быстрые движения»;
- `GuardianEatBreadGoal.tick()` лечит 0.2 HP за 2 тика ⇒ вдвое медленнее задуманного;
- реакция на смену цели/состояния — до **2 тиков** задержки (состояние меняется в нашем `tick()` каждый тик, а голы смотрят на него раз в 2 тика);
- «стоит перед зомби и стоит так секунду, пока не ударят» — это в точности сумма этих задержек плюс собственные windup/кулдаун наших ударов.

**⇒ Фикс — одна строка на гол: `requiresUpdateEveryTick()` → `true`.** Ванильный `MeleeAttackGoal` так и делает (F5). Делается **первым** (шаг R0.0), ещё до переписывания механик, — чтобы отделить эффект частоты тика от эффекта новой логики.

### Пункт 2 — зависание в воздухе

**2.1. Мы каждый тик перезаписываем горизонтальную скорость.**
[`moveDirectly()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1078) в конце:

```java
this.setDeltaMovement(dirX * this.moveSpeed, delta.y, dirZ * this.moveSpeed);
```

Отбрасывание (F8) добавляет подброс в `delta.y` и горизонтальный импульс. Наша перезапись **гасит горизонтальную часть отброса**, но **подброс остаётся** ⇒ страж улетает вверх и не может рулить собой (`damageStunTicks`). Ванильная физика (трение, гравитация, `handleRelativeFrictionAndGravity`) при этом не работает, потому что мы перетираем её результат.

**2.2. Ванильный `MoveControl` прыгает, когда моб «внутри блока» (F6).**
Наш путь перестраивается каждые `NAV_RETARGET_INTERVAL = 10` тиков и всякий раз при `navigation.isDone()` ⇒ `setWantedPosition` ⇒ `operation = MOVE_TO` ⇒ срабатывает проверка «я в блоке с коллизией» ⇒ `jump()`. Операция `JUMPING` снимается только при `onGround()`.

**2.3. Симптом.** Страж подпрыгивает на месте при перестроении пути, а наша перезапись скорости не даёт ему нормально двигаться/приземляться — визуально «висит в воздухе». Редкость объясняется тем, что нужно совпадение: удар + перестроение пути + блок под ногами.

**2.4. Почему это надо чинить именно так.** Убирая ручную перезапись скорости (R0.2), мы возвращаем ванильную физику: отброс отрабатывает как положено, трение/инерция работают, а прыжок (F6) остаётся **ровно тем же** — владелец просил его не трогать, и мы его не трогаем.

### Пункт 3 — ближний бой

**Историческое описание (до R0.3).** Раньше [`GuardianMeleeAttackGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java) был самописным: свой windup, своя анимация через `DATA_SWING_START`, свой расчёт дистанции, свой подход. Ванильный `MeleeAttackGoal` (F5) делает всё то же, **включая дефолтную анимацию удара** (`mob.swing(MAIN_HAND)` ⇒ `LivingEntity.swingAnimation` ⇒ `HumanoidModel.setupAttackAnimation`).

**Текущее состояние.** R0.3 выполнен: гол наследует ванильный `MeleeAttackGoal`, `DATA_SWING_START` в проекте отсутствует, анимацию рисует ванильная `setupAttackAnimation`.

**Историческая цена подхода (до R0.3):** дублирование `HumanoidModel.setupAttackAnimation` своей фазой (`GuardianModel.setupAnim` руками перезаписывал `rightArm.xRot/yRot/zRot`), плюс `DATA_SWING_START`, `getSyncedSwingStart()`, `startMeleeSwing()`, `swingStartLocal`, `lastSwingStart`, `MELEE_SWING_ANIM_TICKS`, `MELEE_SWING_STRIKE_PHASE`, `MELEE_SWING_RETURN_TICKS`, `MELEE_WINDUP_TICKS` — около 150 строк ради «своего» взмаха, который выглядел хуже ванильного. Всё перечисленное удалено.

**⇒ Заменено подклассом ванильного `MeleeAttackGoal`** — это ровно то «не изобретать колесо», о котором просил владелец.

### Пункт 4 — конфликты приоритетов

Найдено **5** конкретных конфликтов:

**К1. Приоритет ≠ порядок.** (F2) `availableGoals` — `LinkedHashSet` ⇒ обход = **порядок регистрации**. `priority` влияет только на `canBeReplacedBy`. Сейчас порядок регистрации случайно совпадает с приоритетами; **любая будущая вставка нового гола в середину сломает поведение незаметно**. Нужен явный контракт: «регистрируем от важного к менее важному» + комментарий в коде.

**К2. Флаг `MOVE` — один на всех.** На `MOVE` претендуют 4 наших гола (`Retreat`, `Melee`, `Ranged`, `EatBread`) + `RandomStrollGoal`. `GoalSelector` отдаёт флаг **только одному** (F3). Кто победит — решает порядок регистрации, а не «логика приоритетов». Нужно: ровно один боевой гол с `MOVE`, остальные без флагов.

**К3. Состояние считается после тика голов.** (1.4) Боевые голы видят состояние прошлого тика ⇒ задержка переключения на 1 тик. Лечится устранением автомата состояний (R0.1): `canUse()` каждого гола смотрит на **живую** цель каждый тик.

**К4. Отдельный автомат состояний дублирует `GoalSelector`.** У нас два «мозга»: `GuardianState` (наш) и `GoalSelector` (ванильный). Любая рассинхронизация = баг. Ванильный `GoalSelector` **и есть** автомат состояний — он уже умеет приоритеты, флаги и прерывание.

**К5. `RandomStrollGoal` вместо `WaterAvoidingRandomStrollGoal`.** Ванильные мобы используют `WaterAvoidingRandomStrollGoal` — он обходит воду. Наш базовый `RandomStrollGoal` этого не делает ⇒ страж может уйти в воду.

### Пункт 5 — оптимизация и рациональность

**Удаляем (прямая экономия кода и работы на сервере):**

| Что | Файлы | Почему |
|-----|-------|--------|
| Самодельная физика движения: `moveDirectly`, `moveAlongPath`, `stopHorizontalMovement`, `moveYaw`/`moveSpeed`/`moveYawValid` | `BaseGuardianEntity.java` | Заменяется ванильными `MoveControl.strafe()` + `navigation.moveTo()` (F7). Уходит и перезапись скорости (2.1) |
| Кастомная анимация удара: `DATA_SWING_START`, `startMeleeSwing()`, `getSyncedSwingStart()`, `swingPhase`, `setSwingPhase()`, ветка `MELEE` в `GuardianModel.setupAnim` | `BaseGuardianEntity.java`, `ModEntityRenderers.java` | Приходит бесплатно от ванильного `MeleeAttackGoal` (F5) |
| Автомат состояний: `GuardianState`, `DATA_AI_STATE`, `updateAIState()`, `CombatState`, `getCombatState()` | `BaseGuardianEntity.java` | `GoalSelector` уже это умеет (К4) |
| `GuardianMeleeAttackGoal` целиком | `entity/ai/` | Заменяется подклассом ванильного `MeleeAttackGoal` |
| `MELEE_WINDUP_TICKS`, `MELEE_SWING_RETURN_TICKS`, `MELEE_SWING_ANIM_TICKS`, `MELEE_SWING_STRIKE_PHASE`, `MOVE_ACCELERATION`, `MOVE_TURN_SPEED_DEG` | `GuardianTuning.java` | Становятся не нужны |
| Флаг `eating` + `isEating()`/`setEating()` | `BaseGuardianEntity.java` | Заменяется на «гол еды активен» + watchdog (R0.4) |

**Не трогаем (осознанно):** заряд/выстрел ([`updateRangedCombat()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:652)) — это наша фишка, и она **вне** голов, поэтому работает при любом состоянии; синхронизацию головы (`DATA_HEAD_PITCH`/`DATA_HEAD_YAW`); систему лагерей; логику прыжка (F6).

**Оптимизация, которую стоит сделать отдельно:** [`GuardianTargetGoal.findNearestMonster()`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianTargetGoal.java:54) вызывает `getEntitiesOfClass` — это перебор всех сущностей уровня. При 9 стражах и ~180 сущностях это ~9 полных переборов в секунду. Оптимизация: (а) сканировать только при отсутствии цели (уже так), (б) интервал 20 → 40 тиков, (в) переиспользовать кэш уровня по образцу [`ModEntityEvents.getGuardians()`](../../../src/main/java/com/phian404/survivalpoints/init/ModEntityEvents.java:50).

### Пункт 6 — что берём из ваниллы (список «кирпичиков»)

| Задача | Ванильный кирпич | Статус |
|--------|------------------|--------|
| Ближний бой | `MeleeAttackGoal` (+ `Mob.setAggressive`, `Mob.doHurtTarget`) | F5, F8, F9 — **берём** |
| Отход лицом к врагу | `MoveControl.strafe(-1, 0)` | F7 — **берём** |
| Подход к цели | `Navigation.moveTo()` / `createPath()` | **берём** |
| Прогулка в покое | `WaterAvoidingRandomStrollGoal` | вместо `RandomStrollGoal` — **берём** |
| Взгляд на игрока | `LookAtPlayerGoal` (уже используется) | ок |
| Взгляд на цель | `LookControl.setLookAt()` (внутри `MeleeAttackGoal`) | **берём** |
| Анимация удара | `HumanoidModel.setupAttackAnimation` (через `mob.swing()`) | **берём** |
| Прыжок | `JumpControl` / `MoveControl` (F6) | **не трогаем** |
| Выбор цели | `TargetingConditions` / `NearestAttackableTargetGoal` | **оценить** на R0.1 (свой сканер даёт контроль над `Monster`-фильтром) |

---

## 2. План работ

Каждый шаг — отдельная проверка владельцем в игре. Коммит — **на границе шага** (политика владельца: не коммитить после каждой правки).

### R0.0 — Подготовка: бэкап, диагностика, флаги (делается ПЕРВЫМ)

**Файлы:** [`GuardianFeatureFlags.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianFeatureFlags.java) (новый), [`GuardianTrace.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTrace.java) (новый), [`GuardDebugCommand.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardDebugCommand.java), [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java)

**Бэкап (уже сделано):** тег `pre-rework-guardian` + ветка `backup/pre-rework-guardian` на коммите `fdb8acb`. Откат реворка = `git checkout backup/pre-rework-guardian -- src/`.

**A. Частота тика (F10).** Переопределить `requiresUpdateEveryTick()` → `true` у `GuardianRangedAttackGoal`, `GuardianMeleeAttackGoal`, `GuardianRetreatGoal`, `GuardianEatBreadGoal`. **Это отдельный замеряемый шаг** — до него фиксируем «как есть», после — сравниваем.

**B. Диагностические команды** (всё пишется и в чат, и в `run/logs/latest.log` с префиксом `[SP]`, чтобы я разбирал лог, а не переспрашивал):

| Команда | Что делает |
|---------|-----------|
| `/sp_guard [radius]` | уже есть; расширить: активные цели + их флаги, цель/дистанция, HP, оружие, `onGround`, `delta.y`, `damageStunTicks`, состояние навигации, `moveSpeed`, рубеж, точка сбора |
| `/sp_guard detail <#>` | полный снимок одного стража: позиция, скорость, `onGround`, `inWater`, `isJumpControlled`, `navigation.isDone()`, `moveControl.operation`, список работающих целей с флагами, цель, HP, экипировка, счётчики (выстрелы/попадания/удары) |
| `/sp_trace on\|off\|clear` | событийный лог стража: смена цели, смена поведения, старт/стоп гола, смена оружия, начало/конец еды, полученный урон, замах мечом + попадание, выстрел, попадание стрелы, отступление/сближение/стоять, прыжок, приземление, перестроение пути |
| `/sp_hang on\|off` | **детектор зависания**: если страж выше земли больше чем на 1.0 блок дольше 10 тиков — пишет в лог предупреждение с полным снимком. Это и есть ответ на «почему зависает» без угадывания |
| `/sp_flag <имя> on\|off\|list` | переключение механик (см. ниже) |

Формат строки в логе (чтобы я мог его грепать):
`[SP] g#3 | goal=GuardianRangedAttackGoal | flags=MOVE,LOOK | target=zombie d=8.3 | hp=18/20 | y=64.0 onGround=1 vy=0.00 | state=RANGED`

**C. Флаги механик** — чтобы временно отключать то, что не трогаем в конкретном шаге, и видеть чистый эффект изменений:

| Флаг | Что отключает | Зачем |
|------|---------------|-------|
| `CUSTOM_MOVE` | самописную физику (`moveDirectly` и т.п.) | A/B-сравнение «своё vs ванильное» в R0.2 |
| `EAT_BREAD` | еду | снять «статую с хлебом» из уравнения в R0.4 |
| `STANDOFF` | удержание рубежа и отступление | изолировать ближний бой в R0.3 |
| `RALLY` | сбор союзников | не мешает замерам боя |
| `LOOK_PLAYER` | взгляд на игрока | не путать с разворотом к врагу |
| `PANIC` | режим паники | не мешает замерам боя |
| `RANGED` | стрельбу | изолировать ближний бой |

Флаги живут в одном классе `GuardianFeatureFlags` (обычный `EnumSet` + команда) и проверяются **в одной точке** каждый — иначе получится «механика работает, а флаг не выключает».

**Проверка владельца после R0.0:** `/sp_flag list` показывает список; `/sp_trace on` + бой ⇒ в логе видны события; `/sp_hang on` + удар по стражу ⇒ либо ловит зависание, либо нет (и это тоже результат).

### R0.1 — Голы как источник истины (убиваем автомат состояний)

> **Разблокирован.** Развилка `GuardianState` решена в пользу стороны A —
> см. [`GUARDIAN_STATE_DECISION.md`](GUARDIAN_STATE_DECISION.md) (полная спецификация,
> риски R1–R12 и порядок шагов). Ниже — сжатая выжимка.

**Файлы:** [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java), [`GuardianTuning.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTuning.java), [`GuardDebugCommand.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardDebugCommand.java), [`ModEntityRenderers.java`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java)

- Убрать `updateAIState()`, `GuardianState`/`CombatState`/`getCombatState()`/`setAIState()`/`isPanicking()`.
  `DATA_AI_STATE` **остаётся только серверным диагностическим кэшем** для `/sp_guard` (логика его не читает).
- Каждый боевой гол в `canUse()`/`canContinueToUse()` проверяет **живую цель и дистанцию сам**; активный гол определяет оружие.
- `syncEquipmentToState()` → «экипировка по активному боевому голу»: меч → меч, иначе арбалет (сохранить ранний выход по `isEating()`, R5).
- Решения автомата переходят в голы: гистерезис `MELEE↔RANGED`, приоритет `PANIC` (производный предикат), исключение для крипера, сброс рубежа (R3/R4).
- Зарегистрировать цели **в порядке важности** и зафиксировать это комментарием (К1); ровно один боевой гол с флагом `MOVE` (К2).

**Рендер (решено):** клиент `GuardianState` **не читает**. Поза арбалета — из `MAINHAND is CROSSBOW` + `isCharging()` + `!isEating()`; тело/голова — новый синхро-флаг `DATA_FACING_TARGET` (сервер из `shouldFaceTarget()`); `isAggressive()` — только «меч vs арбалет». Подробно — [`GUARDIAN_STATE_DECISION.md`](GUARDIAN_STATE_DECISION.md) §3.3.

**Проверка владельца:** страж в покое стоит/гуляет; при появлении зомби — достаёт арбалет и стреляет; при подходе вплотную — достаёт меч; в п��нике — снова арбалет.

### R0.2 — Движение на ванили (убиваем самописную физику)

**Файлы:** [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java), [`GuardianRangedAttackGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRangedAttackGoal.java), [`GuardianRetreatGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRetreatGoal.java), [`GuardianMeleeAttackGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java)

- Удалить `moveDirectly()`, `moveAlongPath()`, `stopHorizontalMovement()` и поля `moveYaw`/`moveSpeed`/`moveYawValid`.
- **Отход** (лицом к врагу) → `strafeWithSpeed(-1, 0, RETREAT_SPEED)`: тело смотрит на врага, шаг делается назад относительно курса тела (F7/F11).
- **Подход** → `getNavigation().moveTo(x, y, z, speed)` + не переписывать скорость; тело ведёт маршрут, **голову** доворачиваем к врагу через `LookControl` (ванильный `clampHeadRotationToBody` — 75°).
- **Стоять на месте** → ничего не вызываем: `MoveControl` в состоянии WAIT обнуляет `zza`, значит страж стоит (F12).
- Снять/ослабить наш анонимный `MoveControl`, который сейчас «возвращает yaw»: с ванильным strafe-движением он не нужен и может конфликтовать (F7 + `MoveControl.tick()` доворачивает курс к пути на 90°/тик).
- Отдельно решить: **кто владеет курсом тела** — путь (ваниль) или враг. Для стрелка-стража правильнее: тело к врагу, движение — strafe. Это и есть «vanilla way» у скелетов/криперов.

**Риск:** смена модели движения меняет ощущение скорости и «резкость» — вручную откаченные владельцем `MOVE_ACCELERATION = 0.04` и `ADVANCE_SPEED = 0.28` уходят вместе с самодельной системой, а ваниль использует атрибут `Attributes.MOVEMENT_SPEED` (надо выставить значение, иначе будет стандартное 0.23).

**Проверка владельца:** нет «пинг-понга»; отход плавный, страж лицом к врагу; нет зависаний в воздухе после удара; скорость выглядит естественно.

**Уточнение R0.2 (по итогам разбора порядка тиков, F11–F14):**

- **Свой `MoveControl`:** `GuardianMoveControl extends MoveControl` с методом `strafeWithSpeed(float forwards, float right, double blocksPerTick)` — вызывает `strafe(...)`, затем переопределяет `speedModifier` (protected), иначе скорость зафиксирована на 0.25 (F13). Туда же переносим «возврат курса» из текущего анонимного подкласса.
- **Не перезаписывать `setDeltaMovement`** — трение, гравитация и отброс остаются ванильными (F14), значит «зависание в воздухе» исчезает по построению, а прыжок (F6) остаётся ровно тем же.
- **Кто владеет курсом тела:** в бою — враг (тело к цели, движение — strafe); в переходе — маршрут. Это «vanilla way» у скелетов и криперов.

| Действие | Вызов | Блок/тик | Множитель при `MOVEMENT_SPEED = 0.30` |
|----------|-------|----------|-------------------------------------|
| Стоять на месте | ничего | 0 | — |
| Отступление | `strafe(-1, 0)` | 0.45 (`RETREAT_SPEED`) | 1.50 |
| Сближение | `navigation.moveTo(..., m)` | 0.28 (`ADVANCE_SPEED`) | 0.93 |
| Шаг в ближнем бою | `strafe(1, 0)` | 0.65 (`MELEE_SPEED`) | 2.17 |

`Attributes.MOVEMENT_SPEED = 0.30` — чуть быстрее зомби (0.23), медленнее игрока. Множители считаются от него, поэтому текущие ощущения скорости (которые владелец уже откалибровал) сохраняются.

### R0.3 — Ближний бой на ванильном `MeleeAttackGoal` + дефолтная анимация

**Файлы:** [`GuardianMeleeAttackGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java), [`ModEntityRenderers.java`](../../../src/main/java/com/phian404/survivalpoints/client/ModEntityRenderers.java), [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java), [`GuardianTuning.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTuning.java)

- `GuardianMeleeAttackGoal extends MeleeAttackGoal`:
  - `super(guardian, MELEE_SPEED, true)`;
  - `canUse()`/`canContinueToUse()` — гейт по дистанции (вход ≤ `MELEE_ENTER_DISTANCE`, выход > `MELEE_EXIT_DISTANCE`) и по живой цели;
  - `checkAndPerformAttack()` — ванильный (F5) либо с нашим уроном, если нужно;
  - `stop()` — гасим навигацию.
- Удалить нашу анимацию взмаха: `DATA_SWING_START`, `startMeleeSwing()`, `getSyncedSwingStart()`, `GuardianModel.setSwingPhase()` и ветку `MELEE` в `setupAnim` (в `setupAnim` остаются только позы арбалета).
- Удалить из `GuardianTuning` `MELEE_WINDUP_TICKS`, `MELEE_SWING_RETURN_TICKS`, `MELEE_SWING_ANIM_TICKS`, `MELEE_SWING_STRIKE_PHASE`.

**Риск:** ванильный `MeleeAttackGoal.canUse()` срабатывает **раз в 20 тиков** ⇒ вход в ближний бой может задержаться до 1 с. Лечится переопределением `canUse()` (он не привязан к `canContinueToUse()`).

**Проверка владельца:** в ближнем бою страж **подходит и бьёт мечом**, анимация удара — **дефолтная ванильная** (рука заносится и бьёт), видно урон, зомби отлетает, страж не стоит столбом секунду.

### R0.4 — Еда: уступка угрозе + watchdog

**Файлы:** [`GuardianEatBreadGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java), [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java), [`GuardianTuning.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTuning.java)

**Сделано (эта сессия).** Владелец выбрал вариант B с обязательной NBT-строкой (см. 1.3). Реализовано ровно то, что снимает наблюдаемый баг «застыл с хлебом после перезахода»:

1. **Флаг `eating` не сериализуется.** `tag.putBoolean("Eating", ...)` удалён из [`addAdditionalSaveData()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1494), чтение `Eating` — из [`readAdditionalSaveData()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1521). Причина: состояние голов не сериализуется, поэтому восстановленный флаг означал «активного гола нет, а `stop()` уже не придёт».
2. **Watchdog-пульс.** Новое поле `eatPulse` + [`pulseEating()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1433), константа [`GuardianTuning.EAT_PULSE_TIMEOUT_TICKS`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTuning.java:37). Гол еды продлевает пульс каждый свой тик; в [`tickCountdowns()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:648) пульс убывает, и при нуле флаг гаснет с записью в `GuardianTrace`. Пока гол тикает — поведение не меняется; если тики оборвались без `stop()` — «залипший» флаг невозможен.
3. **Прерывание еды угрозой.** [`canContinueToUse()`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:106) рвёт еду при появлении цели **и** при `getDamageStunTicks() > 0`. Счётчик урона выставляется в `hurt()` независимо от источника, поэтому огонь, падение и лава тоже прерывают еду — раньше это не покрывалось (см. 1.2).

**Осознанно отложено до R0.1:**

- `canUse()` всё ещё требует только `getTarget() == null`. Проверка «рядом нет монстров» требует переиспользования выборки `GuardianTargetGoal` — это часть К4 (устранение второго «мозга»), а не точечная правка еды.
- Ранний выход `syncEquipmentToState()` по `isEating()` **сохранён намеренно**: сейчас `eating` достижим только при активном голе еды, а во время еды оружие трогать нельзя (иначе хлеб в руке подменялся бы арбалетом на середине анимации). Уйдёт вместе с полем `eating`, когда активный гол станет источником истины.
- `onLivingHurt` не менялся: `setTarget(attacker)` уже есть, а прерывание еды теперь обеспечивает пункт 3 — дублировать не нужно.

Строка в шапке плана помечена ⚠️ именно из-за двух отложенных пунктов.

**Проверка владельца:** раненый страж **не сидит с хлебом под дождём ударов** — сразу встаёт в строй; в спокойе доедает хлеб и возвращается к арбалету.
**Отдельная проверка регрессии:** ранить стража в тишине, дать поесть, **выйти из мира и вернуться** — страж стоит с арбалетом, а не с хлебом, и снова реагирует на зомби.

### R0.5 — Порядок, флаги, приоритеты + диагностика

**Файлы:** [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java) (метод `registerGoals()`), [`GuardianTuning.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTuning.java), [`GuardDebugCommand.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardDebugCommand.java)

- Зафиксировать порядок регистрации и **прокомментировать** (К1): паника → ближний бой → дальний бой → сбор → еда → прогулка → взгляд.
- Ровно один боевой гол с флагом `MOVE` (К2); остальные — без флагов или только `LOOK`.
- `RandomStrollGoal` → `WaterAvoidingRandomStrollGoal` (К5).
- Расширить `/sp_guard`: активные цели, активный гол, дистанция, `damageStunTicks`, `onGround`, `delta.y`, состояние навигации, флаги целей. Это нужно и для пункта 2 — чтобы поймать редкий случай, а не гадать.

### R0.6 — Замеры и приёмка

- `/sp_perf on` + бой на 3+ стражах ⇒ убедиться, что TPS 20.0 и наш код < 2 мс/с.
- Пройти чек-лист владельца (ниже).
- Обновить [`TESTING.md`](../Testing/TESTING.md) и [`AI_CONTEXT.md`](../../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md).

---

## 3. Чек-лист приёмки (для владельца)

| # | Сценарий | Ожидание |
|---|----------|----------|
| 1 | Страж в покое, рядом монстр | Достаёт арбалет, занимает рубеж, стреляет |
| 2 | Монстр подошёл вплотную | Достаёт меч, подходит, **бьёт с дефолтной анимацией** |
| 3 | Страж ранен, монстры рядом | **Не ест хлеб**, сразу в бой |
| 4 | Страж ранен, вокруг тихо | Ест хлеб, потом возвращается к арбалету |
| 5 | Удар по стражу | Отлетает, **падает на землю**, не зависает |
| 6 | Отход | Пятится **лицом к врагу**, плавно, без «пинг-понга» |
| 7 | Страж в воде | Не тонет, выплывает |
| 8 | 3+ стража в бою | TPS 20.0, нет рывков мобов |

---

## 4. Чего НЕ делаем в этой волне

- не трогаем логику прыжка (F6) — владелец просил;
- не трогаем заряд/выстрел и позы арбалета;
- не трогаем систему лагерей (`CampManager` и далее);
- не трогаем `DATA_HEAD_PITCH`/`DATA_HEAD_YAW` (синхронизация головы);
- не возвращаемся к волне 2 (забор/ворота) до закрытия реворка.

---

## 5. Решения, принятые владельцем

| Вопрос | Решение |
|--------|---------|
| Бэкап перед реворком | Сделано: тег `pre-rework-guardian` + ветка `backup/pre-rework-guardian` на `fdb8acb`. Откат: `git checkout backup/pre-rework-guardian -- src/` |
| Диагностика | Команды `/sp_guard detail`, `/sp_trace`, `/sp_hang` + расширенный `/sp_guard`. Всё дублируется в `run/logs/latest.log` с префиксом `[SP]` — выводы делаю по логу, а не по пересказу |
| Временное отключение механик | Флаги `CUSTOM_MOVE`, `EAT_BREAD`, `STANDOFF`, `RALLY`, `LOOK_PLAYER`, `PANIC`, `RANGED` + команда `/sp_flag`. Флага `CUSTOM_SWING` нет: кастомная анимация взмаха удалена вместе с R0.3 |
| `Attributes.MOVEMENT_SPEED` | **0.30** (решение делегировано владельцем). Множители скоростей — из таблицы в R0.2, текущие ощущения скорости сохраняются |
| Логика прыжка | Не трогаем (F6) — остаётся ванильная |
