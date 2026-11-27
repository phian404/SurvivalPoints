package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;

/**
 * События изменения блоков внутри слотов лагеря.
 *
 * <p>Игрок сломал блок в слоте или поставил свой — слот помечается
 * {@code modified = true} и хранилище сохраняется. Флаг защищает изменения
 * игрока: апгрейд слота ({@link CampGenerator#upgradeFarm}) отказывает, пока
 * флаг стоит, чтобы апгрейд молча не стёр постройку игрока.
 *
 * <p>События запускаются только на сервере: клиентские копии слотов
 * отсутствуют, и {@code setDirty()} имеет смысл лишь для серверного уровня.
 */
@Mod.EventBusSubscriber(modid = SurvivalPointsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CampEvents {

    private CampEvents() {
    }

    /**
     * Игрок сломал блок внутри слота — слот изменён.
     *
     * <p>Воздух/уничтожение {@code structure_block} не учитываются: их не
     * было в мире к моменту события. Слом пустого места (воздуха) невозможен.
     *
     * @param event событие разрушения
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        markSlotModified(event.getLevel(), event.getPos());
        invalidateFarmPlot(event.getLevel(), event.getPos());
    }

    /** Рост культуры завершился: обновляем только соответствующую грядку. */
    @SubscribeEvent
    public static void onCropGrow(BlockEvent.CropGrowEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        refreshFarmPlot(level, event.getPos().below());
    }

    /**
     * Сущность поставила блок внутри слота — слот изменён.
     *
     * <p>Фильтр по {@code Player}: блоки от генератора модуля ставятся
     * программно ({@code level.setBlock}), эти события не порождают;
     * {@code EntityPlaceEvent} срабатывает на реальные установки сущностью —
     * в основном игроком.
     *
     * @param event событие установки
     */
    /**
     * Защита пашни от вытаптывания NPC-фермерами.
     *
     * <p>Отменяется только событие от фермера с привязкой к лагерю. Игроки,
     * обычные мобы и NPC других профессий не затрагиваются; глобальный
     * {@code mobGriefing} не меняется.
     */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getEntity() instanceof BaseSurvivorEntity npc)) {
            return;
        }
        if (npc.getProfession() == SurvivorProfession.FARMER && npc.hasCampBinding()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        LevelAccessor accessor = event.getLevel();
        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return;
        }
        markSlotModified(accessor, event.getPos());
        if (accessor instanceof ServerLevel level) {
            refreshFarmPlot(level, event.getPos());
            refreshFarmPlot(level, event.getPos().below());
        }
    }

    /** Удалить устаревшую задачу до фактического разрушения блока. */
    private static void invalidateFarmPlot(LevelAccessor accessor, BlockPos changedPos) {
        if (!(accessor instanceof ServerLevel level)) {
            return;
        }
        CampRecord record = CampManager.findCampAt(level, changedPos);
        if (record == null) {
            record = CampManager.findCampAt(level, changedPos.below());
        }
        if (record != null) {
            CampManager.farmManager(record).invalidatePlotAt(changedPos);
        }
    }

    /** Пересчитать POI грядки после изменения блока. */
    private static void refreshFarmPlot(ServerLevel level, BlockPos plotOrCropPos) {
        CampRecord record = CampManager.findCampAt(level, plotOrCropPos);
        if (record == null) {
            record = CampManager.findCampAt(level, plotOrCropPos.below());
        }
        if (record == null) {
            return;
        }
        CampManager.farmManager(record).refreshPlot(
                level, record, plotOrCropPos, level.getGameTime());
    }

    /**
     * Пометить слот, содержащий точку, как изменённый.
     *
     * @param accessor уровень события
     * @param pos      точка изменения
     */
    private static void markSlotModified(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level)) {
            return;
        }
        CampRecord record = CampManager.findCampAt(level, pos);
        if (record == null) {
            return;
        }
        CampModuleSlot slot = CampManager.slotAt(record, pos);
        if (slot != null && !slot.isModified()) {
            slot.setModified(true);
            CampManager.data(level).setDirty();
        }
    }
}