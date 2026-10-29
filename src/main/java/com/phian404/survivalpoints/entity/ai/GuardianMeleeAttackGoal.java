package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTrace;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * Ближний бой на ванильном {@link MeleeAttackGoal}.
 *
 * <p>Свой гол был самописной копией ванильной механики: свой замах, своя
 * анимация взмаха, свой расчёт дистанции, свой подход. Выглядело криво и
 * тянуло за собой ~150 строк кода (собственный рендер взмаха, своя синхронизация
 * фазы удара).
 *
 * <p>Ванильный гол уже умеет всё нужное: строит маршрут к цели, ведёт моба по
 * нему, доворачивает взгляд через {@code LookControl} и бьёт через
 * {@code mob.swing()} + {@code doHurtTarget()} — вместе с ДЕФОЛТНОЙ анимацией
 * удара.
 *
 * <p>Переопределяем только гейт по дистанции: когда вступать в ближний бой и
 * когда из него выходить.
 */
public class GuardianMeleeAttackGoal extends MeleeAttackGoal {

    private final BaseGuardianEntity guardian;

    /** Тиков подряд без удара — для диагностики «страж перестал бить». */
    private int idleTicks = 0;

    public GuardianMeleeAttackGoal(BaseGuardianEntity guardian) {
        super(guardian, GuardianTuning.MELEE_SPEED_MODIFIER, true);
        this.guardian = guardian;
        // Ванильный MeleeAttackGoal флагов не объявляет — значит, он не берёт
        // MOVE и мирно сосуществует с голом дальнего боя. Без флагов оба гола
        // работали бы одновременно и толкали стража в разные стороны.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.guardian.isAlive()) {
            return false;
        }
        // Паника имеет приоритет над всем — в ней страж отходит с арбалетом,
        // меч не достаёт.
        if (this.guardian.isPanicking()) {
            return false;
        }
        LivingEntity target = this.guardian.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // Против крипера держим арбалет даже вплотную: подходить к нему с мечом
        // смертельно. Ranged-гол сам отступит, если крипер подойдёт слишком близко.
        if (target instanceof Creeper) {
            return false;
        }
        return this.guardian.distanceTo(target) <= GuardianTuning.MELEE_ENTER_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.guardian.isAlive() || this.guardian.isPanicking()) {
            return false;
        }
        LivingEntity target = this.guardian.getTarget();
        // Гистерезис удержан: из боя выходим только на дальней дистанции,
        // иначе на границе включения страж дёргался меч ↔ арбалет.
        return target != null && target.isAlive()
                && !(target instanceof Creeper)
                && this.guardian.distanceTo(target) <= GuardianTuning.MELEE_EXIT_DISTANCE;
    }

    /**
     * Старт ближнего боя.
     *
     * <p>Ванильный {@code start()} строит путь и может получить {@code null}
     * (маршрута нет) — тогда он падает. Поэтому строим путь сами, с проверкой.
     */

    @Override
    public void stop() {
        this.guardian.setAggressive(false);
        this.guardian.getNavigation().stop();
        this.guardian.standStill();
    }

    /**
     * Диагностика «страж перестал бить».
     *
     * <p>Наблюдение владельца: страж стоит, смотрит и не бьёт. По снимку
     * {@code /sp_guard detail} видно {@code distanceTo(target)=1.5},
     * {@code op=WAIT}, {@code navTo} застыл на старой точке.
     *
     * <p>Проверяемое объяснение: цель стоит ровно за границей удара.
     * {@code getMeleeAttackRangeSqr} = {@code (bbWidth*2)^2 + targetWidth}, для
     * стража это ≈2.04, а {@code distanceToSqr(1.5)} = 2.25. При этом ванильный
     * {@code tick()} пересчитывает путь только когда цель сдвинулась на ≥1 блок
     * (по квадрату) или с шансом 5%/тик — неподвижную цель страж «не догоняет».
     *
     * <p>Пишем в лог расстояния и состояние навигации, чтобы подтвердить или
     * опровергнуть это числами, а не догадками. Геймплей не меняется.
     */
    @Override
    public void tick() {
        super.tick();

        LivingEntity target = this.guardian.getTarget();
        if (target == null) {
            this.idleTicks = 0;
            return;
        }

        // Кулдаун атаки — это НЕ «перестал бить». По логу видно, что первая
        // версия детектора давала 118 ложных срабатываний именно из-за этого:
        // страж был в досягаемости, но nextAttack=16…18, то есть ещё заряжал
        // удар. Считаем только состояние «готов бить, но не достаёт».
        if (this.getTicksUntilNextAttack() > 0) {
            this.idleTicks = 0;
            return;
        }

        // Достаёт — ударит на этом тике, серия прерывается.
        if (this.guardian.isWithinMeleeAttackRange(target)) {
            this.idleTicks = 0;
            return;
        }

        // --- Возврат подхода, когда маршрут исчерпан, а цель не достаётся ---
        //
        // Данные из логов: страж доходит до последнего узла маршрута и стоит,
        // потому что узел оказался в 1.7 блока от врага при радиусе удара 1.43.
        // Ваниль путь при этом не пересчитывает: цель неподвижна, а 5 %-шанс
        // за 19 секунд ничего не дал (gPos не менялся ни на сантиметр).
        //
        // Просим путь к точке ВНУТРИ радиуса удара. Частоту ограничиваем,
        // чтобы не строить маршрут каждый тик.

        this.idleTicks++;
        if (this.idleTicks < GuardianTuning.MELEE_IDLE_REPORT_TICKS) {
            return;
        }
        this.idleTicks = 0;

        double distSqr = this.guardian.distanceToSqr(target);
        double reachSqr = this.guardian.getMeleeAttackRangeSqr(target);

        // Замер M3: курс тела против курса на цель.
        //
        // Гипотеза C1: applyFacing() каждый тик ставит setYRot(курс на цель)
        // ПОСЛЕ moveControl.tick(), а travel() → moveRelative() считает
        // направление шага по текущему yRot. Тогда маршрут есть, но страж
        // едет не по узлам, а «в сторону цели» — и упирается в угол.
        //
        // В прежних отчётах курса не было, поэтому гипотезу нельзя было
        // ни подтвердить, ни опровергнуть. Теперь в лог попадают оба курса
        // и факт «тело держится к цели» (bodyFacesTarget).
        GuardianTrace.always("НЕ БЬЁТ g" + this.guardian.getId()
                + " | d=" + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSqr))
                + " | dSqr=" + String.format(java.util.Locale.ROOT, "%.2f", distSqr)
                + " | reachSqr=" + String.format(java.util.Locale.ROOT, "%.2f", reachSqr)
                + " | вДосягаемости=" + (distSqr <= reachSqr)
                + " | nextAttack=" + this.getTicksUntilNextAttack()
                + " | navDone=" + this.guardian.getNavigation().isDone()
                // Замер M7: где КОНЧАЕТСЯ маршрут и достигнут ли он.
                //
                // Прогон показал, что страж стоит ровно на конечном узле пути,
                // но узел этот — в 1.7 блока от врага при радиусе удара 1.43.
                // То есть навигация «справилась», а дотянуться всё равно нельзя.
                // Проверяем уже числами: расстояние до конечного узла и
                // признак «маршрут исчерпан».
                + " | конУзел=" + this.guardian.describePathEnd()
                + " | путьИсчерпан=" + this.guardian.isPathExhausted()
                + " | порогНав=" + String.format(java.util.Locale.ROOT, "%.2f",
                        this.guardian.getNavigation().getMaxDistanceToWaypoint())
                + " | opЗаказ=" + this.guardian.getMoveControlOperationBeforeTick()
                + " | opПосле=" + this.guardian.getMoveControlOperationAfterTick()
                + " | navTo=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f",
                        this.guardian.getMoveControl().getWantedX(),
                        this.guardian.getMoveControl().getWantedZ())
                + " | gPos=" + String.format(java.util.Locale.ROOT, "%.1f/%.1f",
                        this.guardian.getX(), this.guardian.getZ())
                + " | yaw=" + String.format(java.util.Locale.ROOT, "%.1f",
                        this.guardian.getYRot())
                + " | bodyYaw=" + String.format(java.util.Locale.ROOT, "%.1f",
                        this.guardian.yBodyRot)
                + " | wantYaw=" + String.format(java.util.Locale.ROOT, "%.1f",
                        this.guardian.getDesiredYaw())
                + " | bodyFaces=" + this.guardian.isBodyFacesTarget()
                + " | zza=" + String.format(java.util.Locale.ROOT, "%.2f", this.guardian.zza)
                + " | xxa=" + String.format(java.util.Locale.ROOT, "%.2f", this.guardian.xxa));
    }

    /**
     * Гол должен тикать каждый тик (20 Гц), а не через тик (10 Гц).
     *
     * <p>Без этого атака задерживается: ванильный {@code Mob.serverAiStep()}
     * тикает голы полностью только на чётных тиках, а на нечётных — только те,
     * у которых {@code requiresUpdateEveryTick() == true}.
     *
     * <p>Ванильный {@link MeleeAttackGoal} переопределяет этот метод и возвращает
     * {@code true} — без этого зомби бил бы игрока в 2 раза реже.
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * Удар мечом.
     *
     * <p>Саму атаку делает ванильный код (свинг + урон + отбрасывание).
     * Переопределяем только чтобы записать событие в лог: по нему видно,
     * что страж реально бьёт, а не только бежит.
     *
     * <p><b>Исправлен двойной удар.</b> Раньше здесь стояли ещё
     * {@code swing(MAIN_HAND)} и {@code playSound(PLAYER_ATTACK_SWEEP)} — а следом
     * вызывается {@code super.checkAndPerformAttack()}, который делает
     * <b>ровно то же самое</b>:
     *
     * <pre>
     * protected void checkAndPerformAttack(LivingEntity target, double distSqr) {
     *     if (distSqr <= reach && ticksUntilNextAttack <= 0) {
     *         resetAttackCooldown();
     *         mob.swing(InteractionHand.MAIN_HAND);   // ← второй свинг
     *         mob.doHurtTarget(target);               // ← единственный урон
     *     }
     * }
     * </pre>
     *
     * <p>Замер M8 это и показал: {@code swings=40 hits=20} — взмахов ровно вдвое
     * больше попаданий на всём прогоне (4/2, 14/7, 22/11, 30/15, 34/17, 40/20).
     * Урон не удваивался (его делает только {@code super}), но <b>анимация
     * взмаха и звук проигрывались дважды за удар</b>.
     *
     * <p>Теперь оставлено только логирование, весь удар — на ванили. Так же
     * устроен ванильный {@code ZombieAttackGoal}: он добавляет поверх
     * {@code MeleeAttackGoal} только подъём руки, а не второй свинг.
     */
    @Override
    protected void checkAndPerformAttack(LivingEntity target, double distanceSqr) {
        double reach = this.guardian.getMeleeAttackRangeSqr(target);
        if (distanceSqr <= reach && this.getTicksUntilNextAttack() <= 0) {
            GuardianTrace.event(this.guardian, "удар мечом");
        }
        super.checkAndPerformAttack(target, distanceSqr);
    }
}
