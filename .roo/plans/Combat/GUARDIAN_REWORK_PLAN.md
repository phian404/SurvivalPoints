# GUARDIAN_REWORK_PLAN — реворк стража

Статус: **план, закрыт по всем шагам.** Сверено с рабочим деревом; шаги ниже отмечены по факту кода,
а не по намерению. Шаги R0.2 и R0.5 **отменены** решением владельца (обоснование — в таблице ниже).

| Шаг | Статус | Подтверждение в коде |
|---|---|---|
| Этап 0 — рубеж в сущности | ✅ выполнен | `standoffReached`/`standoffAnchor` — поля сущности, сохраняются в NBT |
| R0.0 — `requiresUpdateEveryTick` у боевых голов | ✅ **выполнен** | Переопределён у всех четырёх: [`GuardianMeleeAttackGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:117), [`GuardianEatBreadGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:129), [`GuardianRangedAttackGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRangedAttackGoal.java:99), [`GuardianRetreatGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRetreatGoal.java:88) ⇒ стрельба и отход обновляются на 20 Гц. **Единственный шаг из R0.0/R0.2/R0.5, оставленный в коде** |
| R0.1 — удалить автомат состояний | ✅ выполнен | `CombatState`/`getCombatState()`/`updateAIState()`/`setAIState()` удалены; `isPanicking()` — вычисляемый предикат; боевые голы гейтятся живой целью и `isMeleeActive()`/`isCombatGoalActive()`; `GuardianState`/`DATA_AI_STATE` оставлены как диагностический кэш (`updateStateCache()`); рендер переведён на `DATA_FACING_TARGET` + предмет в руке. См. [`GUARDIAN_STATE_DECISION.md`](GUARDIAN_STATE_DECISION.md) |
| R0.2 — движение на ванили | ⛔ **отменён владельцем** | Шаг закрыт **без выполнения**. Попытка удалить самописную физику дала регрессии (тряска в углу, подскок после удара, утопление, резкий отход), черновик откачен в `stash` и удалён. Главный повод для шага — «зависание в воздухе» — оказался **не дефектом мода**: разбор в [`guardian-movement-defects-plan.md`](guardian-movement-defects-plan.md) показал ванильный `knockback` в замкнутом объёме (подъём 0.96 блока, ~0.5 с). Самописная физика [`moveDirectly()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1613) остаётся штатной, флаг `CUSTOM_MOVE` — включён |
| R0.3 — ближний бой на ванильном `MeleeAttackGoal` | ✅ выполнен | [`GuardianMeleeAttackGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java:31) наследует ванильный гол; кастомная анимация взмаха удалена |
| R0.4 — еда: уступка угрозе + watchdog | ✅ **выполнен** | Флаг `eating` не сериализуется; watchdog-пульс (`EAT_PULSE_TIMEOUT_TICKS`) в [`tickCountdowns()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:648); `canContinueToUse()` рвёт еду по цели **и** по `damageStunTicks > 0` ([`GuardianEatBreadGoal`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:103)). Оба симптома подтверждены владельцем в игре: еда прерывается при появлении цели, зависания с хлебом при перезаходе нет. Два «хвоста» из плана закрыты осознанно без кода: скан «монстр рядом» в `canUse()` не нужен (см. R0.4), ранний выход `syncEquipmentToState()` по `isEating()` корректен и сохранён |
| R0.5 — порядок голов, флаги, `WaterAvoidingRandomStrollGoal` | ⛔ **отменён владельцем** | Выполнена только первая часть (порядок регистрации закомментирован в R0.1, [`registerGoals()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:376)); `RandomStrollGoal` и контракт флагов `MOVE` **оставлены как есть**. Причина отмены: архитектурный вопрос К2 не решён — механическое снятие флагов меняет вытеснение голов, а наблюдаемых дефектов это не устраняет |
| R0.6 — замеры и приёмка | ✅ **выполнен** | Приёмка на владельце: чек-лист из 8 сценариев пройден в игре (покой/монстр, подход вплотную, раненый с монстрами, раненый в тишине, удар/отлёт, отход лицом, вода, 3+ стража в бою). Замеры производительности — `/sp_perf` (см. [`TESTING.md`](../Testing/TESTING.md)) |

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
Итоговые причины: (а) автомат состояний имеет приоритет над боевыми голами — `eating` принудительно переводит в `IDLE`, оружие не выдаётся; (б) гол еды уступает только цели, цель появляется с задержкой и не от всех источников урона; (в) флаг `eating` не имеет watchdog и переживает перезагрузку мира. Лечится устранением автомата состояний (R0.1) и watchdog (R0.4).

### Пункт 1.5 — резкость и задержки
Голы тикают 10 Гц вместо 20 Гц (нет `requiresUpdateEveryTick()`). Фикс — R0.0 A.

### Пункт 2 — зависание в воздухе
Опровергнуто: причина — ванильный `knockback` в замкнутом объёме. Самописная физика остаётся (R0.2 отменён).

### Пункт 7 — недостижимая цель (под землёй)
Цель выбирается без учёта достижимости; отход считает 3D-расстояние; при отсутствии LOS нет выстрела; зов бесконечный. Исправлено (A+C): счётчик `noLineOfSightTicks` сбрасывает цель; зов только при LOS; `SCREAM_INTERVAL=200`; память о недостижимых целях с авто-снятием.

### Пункт 3 — ближний бой
Самописный гол заменён подклассом ванильного `MeleeAttackGoal` (R0.3); кастомная анимация взмаха удалена.

### Пункт 4 — конфликты приоритетов
Порядок регистрации ≠ priority (K1); один флаг `MOVE` на всех (K2); состояние считается после тика голов (K3); отдельный автомат дублирует `GoalSelector` (K4); `RandomStrollGoal` вместо `WaterAvoiding` (K5). Порядок регистрации прокомментирован (R0.1); остальное отменено (R0.5).

### Пункт 5 — оптимизация
Удалить кастомную анимацию удара (R0.3), автомат состояний (R0.1), `GuardianMeleeAttackGoal` (R0.3), флаг `eating` (R0.4 частично); оптимизировать `GuardianTargetGoal.findNearestMonster()`. Самописная физика сохранена (R0.2 отменён).

### Пункт 6 — ванильные кирпичики
Берём: `MeleeAttackGoal`, `MoveControl.strafe`, `Navigation`, `WaterAvoidingRandomStrollGoal`, `LookControl`, `HumanoidModel.setupAttackAnimation`. Прыжок не трогаем.

---

## 2. План работ

Каждый шаг — отдельная проверка владельцем в игре. Коммит — **на границе шага** (политика владельца: не коммитить после каждой правки).

### R0.0 — Подготовка: бэкап, диагностика, флаги (делается ПЕРВЫМ) ✅

**Файлы:** [`GuardianFeatureFlags.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianFeatureFlags.java) (новый), [`GuardianTrace.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTrace.java) (новый), [`GuardDebugCommand.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardDebugCommand.java), [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java)

**Бэкап (уже сделано):** тег `pre-rework-guardian` + ветка `backup/pre-rework-guardian` на коммите `fdb8acb`. Откат реворка = `git checkout backup/pre-rework-guardian -- src/`.

**A. Частота тика (F10).** ✅ Переопределить `requiresUpdateEveryTick()` → `true` у `GuardianRangedAttackGoal`, `GuardianMeleeAttackGoal`, `GuardianRetreatGoal`, `GuardianEatBreadGoal`. **Это отдельный замеряемый шаг** — до него фиксируем «как есть», после — сравниваем.

**B. Диагностические команды** ✅ (всё пишется и в чат, и в `run/logs/latest.log` с префиксом `[SP]`, чтобы я разбирал лог, а не переспрашивал):

| Команда | Что делает | Статус |
|---------|-----------|---------|
| `/sp_guard [radius]` | уже есть; расширить: активные цели + их флаги, цель/дистанция, HP, оружие, `onGround`, `delta.y`, `damageStunTicks`, состояние навигации, `moveSpeed`, рубеж, точка сбора | ✅ |
| `/sp_guard detail <#>` | полный снимок одного стража: позиция, скорость, `onGround`, `inWater`, `isJumpControlled`, `navigation.isDone()`, `moveControl.operation`, список работающих целей с флагами, цель, HP, экипировка, счётчики (выстрелы/попадания/удары) | ✅ |
| `/sp_trace on\|off\|clear` | событийный лог стража: смена цели, смена поведения, старт/стоп гола, смена оружия, начало/конец еды, полученный урон, замах мечом + попадание, выстрел, попадание стрелы, отступление/сближение/стоять, прыжок, приземление, перестроение пути | ✅ |
| `/sp_hang on\|off` | **детектор зависания**: если страж выше земли больше чем на 1.0 блок дольше 10 тиков — пишет в лог предупреждение с полным снимком. Это и есть ответ на «почему зависает» без угадывания | ✅ |
| `/sp_flag <имя> on\|off\|list` | переключение механик (см. ниже) | ✅ |

Формат строки в логе (чтобы я мог его грепать):
`[SP] g#3 | goal=GuardianRangedAttackGoal | flags=MOVE,LOOK | target=zombie d=8.3 | hp=18/20 | y=64.0 onGround=1 vy=0.00 | state=RANGED`

**C. Флаги механик** ✅ — чтобы временно отключать то, что не трогаем в конкретном шаге, и видеть чистый эффект изменений:

| Флаг | Что отключает | Зачем | Статус |
|------|---------------|-------|---------|
| `CUSTOM_MOVE` | самописную физику (`moveDirectly` и т.п.) | A/B-сравнение «своё vs ванильное» (R0.2 закрыт — флаг остаётся диагностическим) | ✅ |
| `EAT_BREAD` | еду | снять «статую с хлебом» из уравнения в R0.4 | ✅ |
| `STANDOFF` | удержание рубежа и отступление | изолировать ближний бой в R0.3 | ✅ |
| `RALLY` | сбор союзников | не мешает замерам боя | ✅ |
| `LOOK_PLAYER` | взгляд на игрока | не путать с разворотом к врагу | ✅ |
| `PANIC` | режим паники | не мешает замерам боя | ✅ |
| `RANGED` | стрельбу | изолировать ближний бой | ✅ |

Флаги живут в одном классе `GuardianFeatureFlags` (обычный `EnumSet` + команда) и проверяются **в одной точке** каждый — иначе получится «механика работает, а флаг не выключает».

**Проверка владельца после R0.0:** `/sp_flag list` показывает список; `/sp_trace on` + бой ⇒ в логе видны события; `/sp_hang on` + удар по стражу ⇒ либо ловит зависание, либо нет (и это тоже результат).

### R0.1 — Голы как источник истины (убиваем автомат состояний) ✅

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

### R0.2 — Движение на ванили — ⛔ ОТМЕНЁН

> **Шаг отменён владельцем, не выполняется.** Раздел оставлен как история решения: он объясняет,
> почему самописная физика остаётся в коде. Пункт «убиваем самописную физику» снят с повестки.
>
> Причины отмены:
> 1. Попытка реализации дала регрессии — тряска в углу, подскок ~0.5 с после удара, утопление, резкий отход.
>    Плавность (сглаженный курс, разгон) и плавучесть — встроенная часть самописной физики, а не отдельные костыли.
> 2. Главный повод для шага («зависание в воздухе») **не является дефектом мода**: разбор
>    в [`guardian-movement-defects-plan.md`](guardian-movement-defects-plan.md) показал ванильный `knockback`
>    в замкнутом объёме (подъём 0.96 блока, ~0.5 с).
>
> Что осталось в коде: [`moveDirectly()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1613)
> — штатный путь движения, флаг `CUSTOM_MOVE` включён, [`GuardianMoveControl.strafeWithSpeed()`](../../../src/main/java/com/phian404/survivalpoints/entity/GuardianMoveControl.java)
> применяется только в отходе. `moveAlongPath()` и `stopHorizontalMovement()` не вызываются — это осознанно
> оставленный код, удаление не планируется.

**Исходные файлы шага (не изменяются):** [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java), [`GuardianRangedAttackGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRangedAttackGoal.java), [`GuardianRetreatGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianRetreatGoal.java), [`GuardianMeleeAttackGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianMeleeAttackGoal.java)

**Планировавшиеся правки (сняты с выполнения, оставлены как история):**

- ~~Удалить `moveDirectly()`, `moveAlongPath()`, `stopHorizontalMovement()` и поля `moveYaw`/`moveSpeed`/`moveYawValid`.~~
- ~~Отход → `strafeWithSpeed(-1, 0, RETREAT_SPEED)`: тело к врагу, шаг назад относительно курса тела (F7/F11).~~
- ~~Подход → `getNavigation().moveTo(x, y, z, speed)` без перезаписи скорости.~~
- ~~Стоять на месте → ничего не вызываем: `MoveControl` в состоянии WAIT обнуляет `zza` (F12).~~
- ~~Снять/ослабить анонимный `MoveControl`, «возвращающий yaw» (F7 + `MoveControl.tick()` доворачивает курс к пути на 90°/тик).~~
- ~~Решить, кто владеет курсом тела — путь или враг.~~

**Почему отменено (факт, а не предположение):** при попытке реализации владелец получил набор регрессий —
вернулась тряска в углу, появился подскок ~0.5 с после удара, страж тонул, отход стал резким.
Причина: плавность (`MOVE_TURN_SPEED_DEG`, `MOVE_ACCELERATION`) и плавучесть (водная ветка `moveDirectly()`)
— встроенная часть самописной физики. Точечные заплатки через `MoveControl`/`setJumping` конфликтуют
с ванильным `isWalkable` и `JumpControl`. Второй повод для шага — «зависание в воздухе» — разобран
как ванильный `knockback` в замкнутом объёме, то есть **дефекта мода нет**.

**Осознанно оставлено как есть:** `moveAlongPath()` и `stopHorizontalMovement()` не вызываются,
но удаление не планируется — это мёртвый код, а не риск.

### R0.3 — Ближний бой на ванильном `MeleeAttackGoal` + дефолтная анимация ✅

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

### R0.4 — Еда: уступка угрозе + watchdog ⚠️

**Файлы:** [`GuardianEatBreadGoal.java`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java), [`BaseGuardianEntity.java`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java), [`GuardianTuning.java`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTuning.java)

**Сделано (эта сессия).** Владелец выбрал вариант B с обязательной NBT-строкой (см. 1.3). Реализовано ровно то, что снимает наблюдаемый баг «застыл с хлебом после перезахода»:

1. **Флаг `eating` не сериализуется.** `tag.putBoolean("Eating", ...)` удалён из [`addAdditionalSaveData()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1494), чтение `Eating` — из [`readAdditionalSaveData()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1521). Причина: состояние голов не сериализуется, поэтому восстановленный флаг означал «активного гола нет, а `stop()` уже не придёт».
2. **Watchdog-пульс.** Новое поле `eatPulse` + [`pulseEating()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:1433), константа [`GuardianTuning.EAT_PULSE_TIMEOUT_TICKS`](../../../src/main/java/com/phian404/survivalpoints/init/GuardianTuning.java:37). Гол еды продлевает пульс каждый свой тик; в [`tickCountdowns()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:648) пульс убывает, и при нуле флаг гаснет с записью в `GuardianTrace`. Пока гол тикает — поведение не меняется; если тики оборвались без `stop()` — «залипший» флаг невозможен.
3. **Прерывание еды угрозой.** [`canContinueToUse()`](../../../src/main/java/com/phian404/survivalpoints/entity/ai/GuardianEatBreadGoal.java:106) рвёт еду при появлении цели **и** при `getDamageStunTicks() > 0`. Счётчик урона выставляется в `hurt()` независимо от источника, поэтому огонь, падение и лава тоже прерывают еду — раньше это не покрывалось (см. 1.2).

**Осознанно отложено до R0.1:**

- ⬜ `canUse()` всё ещё требует только `getTarget() == null`. Проверка «рядом нет монстров» требует переиспользования выборки `GuardianTargetGoal` — это часть К4 (устранение второго «мозга»), а не точечная правка еды.
- ⬜ Ранний выход `syncEquipmentToState()` по `isEating()` **сохранён намеренно**: сейчас `eating` достижим только при активном голе еды, а во время еды оружие трогать нельзя (иначе хлеб в руке подменялся бы арбалетом на середине анимации). Уйдёт вместе с полем `eating`, когда активный гол станет источником истины.
- ⬜ `onLivingHurt` не менялся: `setTarget(attacker)` уже есть, а прерывание еды теперь обеспечивает пункт 3 — дублировать не нужно.

Строка в шапке плана помечена ⚠️ именно из-за двух отложенных пунктов.

**Проверка владельца:** раненый страж **не сидит с хлебом под дождём ударов** — сразу встаёт в строй; в спокойе доедает хлеб и возвращается к арбалету.
**Отдельная проверка регрессии:** ранить стража в тишине, дать поесть, **выйти из мира и вернуться** — страж стоит с арбалетом, а не с хлебом, и снова реагирует на зомби.

### R0.5 — Порядок, флаги, приоритеты + диагностика — ⛔ ОТМЕНЁН

> **Шаг отменён владельцем.** Выполнена только первая часть — порядок регистрации голов зафиксирован
> и прокомментирован ещё в R0.1 ([`registerGoals()`](../../../src/main/java/com/phian404/survivalpoints/entity/BaseGuardianEntity.java:376)).
> Остальные пункты **не выполняются**.

**Что было запланировано и снято:**

- ~~Ровно один боевой гол с флагом `MOVE` (К2); остальные — без флагов или только `LOOK`.~~
  Причина отмены: К2 — нерешённый архитектурный вопрос. Флаги `MOVE` определяют **вытеснение** голов
  в `GoalSelector`; механическое снятие у трёх из четырёх меняет приоритеты и может дать новые дефекты.
  Наблюдаемых проблем это не устраняет — менять «на всякий случай» запрещено правилами.
- ~~`RandomStrollGoal` → `WaterAvoidingRandomStrollGoal` (К5).~~
  Причина отмены: шаг был частью непроверенного пакета вместе с R0.2; отдельного дефекта не закрывает.
- ~~Расширить `/sp_guard`.~~ Выполнено иначе и раньше: диагностика закрыта командой
  [`/sp_snap`](../../../src/main/java/com/phian404/survivalpoints/init/GuardDebugCommand.java) (след движения 32 тика,
  детекторы `ОТБРОС`/`ПОЛУБЛОК`/`ПРЫЖОК ЗАЛИП`/`ПОДСКОК`/`ТОНЕТ`, метрики `M8-*`) и уже существующими
  `/sp_guard detail`, `/sp_trace`, `/sp_hang`.

### R0.6 — Замеры и приёмка — ✅ ВЫПОЛНЕН

> **Шаг закрыт владельцем.** Чек-лист из 8 сценариев пройден в игре, реворк принят.
> Пункты ниже — как это проверялось.

- `/sp_perf on` + бой на 3+ стражах ⇒ убедиться, что TPS 20.0 и наш код < 2 мс/с.
- Пройти чек-лист владельца (ниже).
- Обновить [`TESTING.md`](../Testing/TESTING.md) и [`AI_CONTEXT.md`](../../../src/main/java/com/phian404/survivalpoints/AI_CONTEXT.md).

---

## 3. Чек-лист приёмки (для владельца)

| # | Сценарий | Ожидание | Статус |
|---|----------|----------|---------|
| 1 | Страж в покое, рядом монстр | Достаёт арбалет, занимает рубеж, стреляет | ✅ |
| 2 | Монстр подошёл вплотную | Достаёт меч, подходит, **бьёт с дефолтной анимацией** | ✅ |
| 3 | Страж ранен, монстры рядом | **Не ест хлеб**, сразу в бой | ✅ |
| 4 | Страж ранен, вокруг тихо | Ест хлеб, потом возвращается к арбалету | ✅ |
| 5 | Удар по стражу | Отлетает, **падает на землю**, не зависает | ✅ |
| 6 | Отход | Пятится **лицом к врагу**, плавно, без «пинг-понга» | ✅ |
| 7 | Страж в воде | Не тонет, выплывает | ✅ |
| 8 | 3+ стража в бою | TPS 20.0, нет рывков мобов | ✅ |

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
| `Attributes.MOVEMENT_SPEED` | **0.30** (решение делегировано владельцем). Множители скоростей — из таблицы в R0.2 (шаг закрыт, таблица осталась как справочная), текущие ощущения скорости сохраняются |
| Логика прыжка | Не трогаем (F6) — остаётся ванильная |
