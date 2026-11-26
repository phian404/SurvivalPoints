package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.UUID;

/**
 * Обычная прогулка стража с проверкой закрытых рабочих модулей лагеря.
 *
 * <p>Проверка действует только на свободную прогулку. Боевые Goal и их
 * навигация остаются без изменений.
 */
public class CampRestrictedRandomStrollGoal extends RandomStrollGoal {

    private final BaseSurvivorEntity npc;

    public CampRestrictedRandomStrollGoal(BaseSurvivorEntity npc, double speedModifier) {
        super(npc, speedModifier);
        this.npc = npc;
    }

    @Override
    public boolean canUse() {
        if (!super.canUse()) {
            return false;
        }
        if (!pathAllowed()) {
            this.npc.getNavigation().stop();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!super.canContinueToUse()) {
            return false;
        }
        if (!pathAllowed()) {
            this.npc.getNavigation().stop();
            return false;
        }
        return true;
    }

    private boolean pathAllowed() {
        if (!(this.npc.level() instanceof ServerLevel level)) {
            return true;
        }
        UUID campId = this.npc.getCampId();
        if (campId == null) {
            return true;
        }
        CampRecord camp = CampManager.getCamp(level, campId);
        if (camp == null) {
            return true;
        }
        Path path = this.npc.getNavigation().getPath();
        return path == null || CampWorkZoneAccess.isWanderPathAllowed(this.npc, camp, path);
    }
}
