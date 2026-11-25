package com.phian404.survivalpoints.init;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.site.CampSiteDirector;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * <p>Теперь список стражей кэшируется отдельно для каждого уровня и обновляется
 * раз в {@link GuardianTuning#ALLY_SEARCH_INTERVAL} тиков.
 */
@Mod.EventBusSubscriber(modid = "survivalpoints", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ModEntityEvents {

    /** Кэш стражей по уровням (ключ — идентичность Level). */
    private record GuardianCacheEntry(List<BaseGuardianEntity> guardians, long cachedTime) {
    }

    private static final Map<Level, GuardianCacheEntry> GUARDIAN_CACHES = new IdentityHashMap<>();

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
        GuardianCacheEntry entry = GUARDIAN_CACHES.get(level);
        if (entry == null || now - entry.cachedTime() >= GuardianTuning.ALLY_SEARCH_INTERVAL) {
            List<BaseGuardianEntity> snapshot = List.copyOf(level.getEntitiesOfClass(
                    BaseGuardianEntity.class, worldBox(level), e -> true));
            entry = new GuardianCacheEntry(snapshot, now);
            GUARDIAN_CACHES.put(level, entry);
        }
        return entry.guardians();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level) {
            GUARDIAN_CACHES.remove(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        GUARDIAN_CACHES.clear();
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

    /**
     * Смерть жителя лагеря: убрать его из состава и освободить пост.
     *
     * <p>Forge вызывает {@link LivingDeathEvent} из {@code LivingEntity.die()}
     * ДО проверки {@code !isRemoved() && !dead}, поэтому событие приходит и при
     * повторном вызове {@code die()} на уже мёртвой сущности. Защита — проверка
     * {@link LivingEntity#isDeadOrDying()}. Отменённая смерть оставляет
     * сущность живой, поэтому дополнительно проверяется {@code isCanceled()}.
     *
     * <p>Замену погибшему не создаём: живые NPC сохраняются штатными средствами
     * Minecraft, мёртвые не восстанавливаются.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof BaseSurvivorEntity npc)
                || !npc.isDeadOrDying() || !(npc.level() instanceof ServerLevel level)) {
            return;
        }

        UUID campId = npc.getCampId();
        if (campId == null) {
            return;
        }
        CampRecord record = CampManager.getCamp(level, campId);
        if (record == null) {
            return;
        }

        UUID identity = npc.getIdentityId();
        record.removeResident(identity);
        record.clearLeaderIf(identity);
        CampManager.data(level).setDirty();
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
        CampSiteDirector.onServerTick(event.getServer());
        PerfDiagnostics.addOurNanos(System.nanoTime() - start);
        PerfDiagnostics.onTickEnd();
    }
}
