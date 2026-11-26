package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampGenerator;
import com.phian404.survivalpoints.camp.CampModuleSlot;
import com.phian404.survivalpoints.camp.CampPost;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;

/**
 * Проверка доступа к рабочим модулям лагеря.
 *
 * <p>Фермер имеет доступ только к назначенному слоту фермы, а страж —
 * к слоту вышки, если именно на вышечный пост назначен его identity UUID.
 * Для уже оказавшегося внутри чужой зоны NPC маршрут может вести наружу,
 * но не может повторно войти в неё после выхода.
 */
public final class CampWorkZoneAccess {

    private CampWorkZoneAccess() {
    }

    /** Можно ли выбрать позицию как новую цель перемещения. */
    public static boolean isPositionAllowed(BaseSurvivorEntity npc, CampRecord camp, BlockPos pos) {
        CampModuleSlot farm = camp.getModuleSlot(CampGenerator.FARM_SLOT);
        if (farm != null && farm.getBounds().contains(pos)) {
            return false;
        }

        CampModuleSlot tower = camp.getModuleSlot(CampGenerator.WATCHTOWER_SLOT);
        return tower == null || hasTowerAccess(npc, camp, tower) || !tower.getBounds().contains(pos);
    }

    /** Находится ли NPC внутри габаритов фермерского модуля. */
    public static boolean isInsideFarmZone(BaseSurvivorEntity npc, CampRecord camp) {
        CampModuleSlot farm = camp.getModuleSlot(CampGenerator.FARM_SLOT);
        return farm != null && farm.getBounds().contains(npc.blockPosition());
    }

    /** Проверяет путь свободной прогулки: ферма закрыта даже для фермера. */
    public static boolean isWanderPathAllowed(BaseSurvivorEntity npc, CampRecord camp, Path path) {
        CampModuleSlot farm = camp.getModuleSlot(CampGenerator.FARM_SLOT);
        if (!pathAllowedForSlot(npc, farm, false, path)) {
            return false;
        }

        CampModuleSlot tower = camp.getModuleSlot(CampGenerator.WATCHTOWER_SLOT);
        return pathAllowedForSlot(npc, tower, hasTowerAccess(npc, camp, tower), path);
    }

    /** Проверяет рабочий путь фермера: ферма разрешена, вышка остаётся закрытой. */
    public static boolean isWorkPathAllowed(BaseSurvivorEntity npc, CampRecord camp, Path path) {
        CampModuleSlot farm = camp.getModuleSlot(CampGenerator.FARM_SLOT);
        if (!pathAllowedForSlot(npc, farm, hasFarmAccess(npc, farm), path)) {
            return false;
        }

        CampModuleSlot tower = camp.getModuleSlot(CampGenerator.WATCHTOWER_SLOT);
        return pathAllowedForSlot(npc, tower, hasTowerAccess(npc, camp, tower), path);
    }

    private static boolean hasFarmAccess(BaseSurvivorEntity npc, @Nullable CampModuleSlot farm) {
        BlockPos workPoi = npc.getWorkPoiPos();
        return farm != null
                && npc.getProfession() == SurvivorProfession.FARMER
                && workPoi != null
                && farm.getBounds().contains(workPoi);
    }

    private static boolean hasTowerAccess(BaseSurvivorEntity npc, CampRecord camp,
                                          @Nullable CampModuleSlot tower) {
        if (tower == null) {
            return false;
        }
        CampPost post = camp.getPostOf(npc.getIdentityId());
        return post != null
                && post.getType() == CampPost.Type.TOWER
                && tower.getBounds().contains(post.getPos());
    }

    /**
     * Запрещает вход в чужую зону. Если NPC уже внутри, разрешает узлы до
     * первого выхода и требует, чтобы остаток маршрута оставался снаружи.
     */
    private static boolean pathAllowedForSlot(BaseSurvivorEntity npc,
                                              @Nullable CampModuleSlot slot,
                                              boolean authorized,
                                              Path path) {
        if (slot == null || authorized) {
            return true;
        }

        CampModuleSlot.SlotBounds bounds = slot.getBounds();
        boolean startedInside = bounds.contains(npc.blockPosition());
        boolean exited = !startedInside;

        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            boolean inside = bounds.contains(path.getNodePos(i));
            if (inside) {
                if (exited) {
                    return false;
                }
            } else if (startedInside) {
                exited = true;
            }
        }
        return !startedInside || exited;
    }
}
