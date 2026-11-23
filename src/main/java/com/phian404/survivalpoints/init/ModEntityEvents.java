package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Глобальные события мода.
 *
 * <p><b>Важно про производительность.</b> Раньше поиск стражей выполнялся
 * {@code getEntitiesOfClass(...)} для КАЖДОГО монстра каждые 20 тиков.
 * В 1.20.1 такой поиск перебирает все сущности уровня, поэтому при 50 зомби
 * и 500 сущностях получалось 25 000 проверок в секунду — TPS падал, и ВСЕ мобы
 * (включая ванильных зомби) начинали двигаться рывками, потому что их тики
 * выполнялись с задержкой.
 *
 * <p>Теперь список стражей кэшируется один раз на весь мир и обновляется
 * раз в {@link GuardianTuning#ALLY_SEARCH_INTERVAL} тиков.
 */
@Mod.EventBusSubscriber(modid = "survivalpoints", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModEntityEvents {

    /** Кэш стражей на текущий уровень. */
    private static final List<BaseGuardianEntity> GUARDIAN_CACHE = new ArrayList<>();

    private static Level cachedLevel = null;
    private static long cachedTime = Long.MIN_VALUE;

    /**
     * Список стражей на уровне.
     *
     * <p>Время жизни кэша считается по игровому времени мира, а не счётчиком
     * вызовов. Иначе при нескольких вызовах за тик (например, по одному на
     * каждого монстра) таймер обнулялся бы и кэш перестраивался каждый раз —
     * то есть полный перебор сущностей мира на каждого монстра. Это роняло
     * клиент до единиц FPS.
     */
    public static List<BaseGuardianEntity> getGuardians(Level level) {
        long now = level.getGameTime();
        if (cachedLevel != level || now - cachedTime >= GuardianTuning.ALLY_SEARCH_INTERVAL) {
            cachedLevel = level;
            cachedTime = now;
            GUARDIAN_CACHE.clear();
            GUARDIAN_CACHE.addAll(level.getEntitiesOfClass(
                    BaseGuardianEntity.class, worldBox(level), e -> true));
        }
        return GUARDIAN_CACHE;
    }

    /** Габариты мира по вертикали, ширина — максимально возможная. */
    private static AABB worldBox(Level level) {
        return new AABB(-3.0E7D, level.getMinBuildHeight(), -3.0E7D,
                3.0E7D, level.getMaxBuildHeight(), 3.0E7D);
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        // Считаем попадания стрел стража в ЛЮБЫЕ цели. Проверка стоит до
        // ветки со стражями, иначе попадания по мобам не учитывались бы.
        // Событие урона приходит даже при нулевом уроне, поэтому здесь видно
        // и «попадание без урона» (hits растёт, а dmg остаётся нулевым).
        if (event.getSource().getDirectEntity() instanceof AbstractArrow arrow
                && arrow.getOwner() instanceof BaseGuardianEntity shooter) {
            shooter.onArrowHit(event.getAmount());
        }

        if (event.getEntity() instanceof BaseGuardianEntity guardian) {
            // ЗАЩИТА ОТ СВОИХ: страж не должен ранить других стражей.
            if (event.getSource().getDirectEntity() instanceof AbstractArrow arrow) {
                if (arrow.getOwner() instanceof BaseGuardianEntity) {
                    event.setCanceled(true);
                    return;
                }
            }

            if (event.getSource().getEntity() instanceof LivingEntity attacker) {
                guardian.setTarget(attacker);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityTick(LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();

        // Событие приходит для каждого живого существа в мире каждый тик.
        // На клиенте выбор цели решает сервер, поэтому здесь сразу выходим —
        // иначе клиент платит за поиск стражей зря.
        if (entity.level().isClientSide) {
            return;
        }

        // Проверка частоты — самая дешёвая операция.
        if (entity.tickCount % 20 != 0) {
            return;
        }

        if (!(entity instanceof Monster monster)) {
            return;
        }

        long start = System.nanoTime();
        List<BaseGuardianEntity> guardians = getGuardians(monster.level());
        PerfDiagnostics.addOurNanos(System.nanoTime() - start);
        if (guardians.isEmpty()) {
            return;
        }

        // Ближайший ВИДИМЫЙ страж в пределах досягаемости. Поиск идёт по кэшу,
        // без обращения к сущностям уровня.
        //
        // Проверка обзора обязательна: без неё монстр за глухой стеной лагеря
        // брал стражей в цель прямо через ограду, и они отвечали, хотя не видели
        // врага. Порядок условий важен — сначала дешёвое сравнение расстояний,
        // и только для прошедших его кандидатов считается клип видимости.
        //
        // Условие «ближайший видимый», а не «ближайший, если видим»: страж
        // за стеной не должен скрывать стража, стоящего на виду в воротах или
        // на вышке, иначе такой монстр вообще не находил бы цели.
        double best = GuardianTuning.TARGET_DETECT_DISTANCE * GuardianTuning.TARGET_DETECT_DISTANCE;
        BaseGuardianEntity nearest = null;
        for (BaseGuardianEntity guardian : guardians) {
            double distance = monster.distanceToSqr(guardian);
            if (distance < best && monster.hasLineOfSight(guardian)) {
                best = distance;
                nearest = guardian;
            }
        }

        if (nearest == null) {
            return;
        }

        LivingEntity current = monster.getTarget();
        if (current == null) {
            monster.setTarget(nearest);
        } else if (current instanceof BaseGuardianEntity
                && best < monster.distanceToSqr(current) * 0.5D) {
            monster.setTarget(nearest);
        }
    }

    /**
     * Тик лагерей.
     *
     * <p>Лагеря тикают раз в секунду (не каждый тик): обновляется режим
     * «день/ночь», зона тревоги и таймеры штата. Свой тик на каждый лагерь
     * не нужен — состояние лагеря меняется медленно, а лишние тики бьют по TPS.
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            PerfDiagnostics.onTickStart();
            return;
        }
        long start = System.nanoTime();
        CampManager.onServerTick(event.getServer());
        PerfDiagnostics.addOurNanos(System.nanoTime() - start);
        PerfDiagnostics.onTickEnd();
    }
}
