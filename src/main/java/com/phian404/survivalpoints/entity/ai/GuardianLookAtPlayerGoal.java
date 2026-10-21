package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Взгляд на игрока — только когда страж не в бою.
 *
 * <p>Ванильный {@link LookAtPlayerGoal} после появления цели продолжает
 * работать: его {@code canUse()} требует отсутствия цели, а
 * {@code canContinueToUse()} проверяет только наличие игрока рядом. Из-за
 * этого {@code LookControl} каждый тик уводил голову стража к игроку, и
 * голова доворачивалась отдельно от тела — страж выглядел так, будто смотрит
 * на игрока, а стреляет в зомби.
 *
 * <p>Здесь гол работает исключительно в состоянии
 * {@link BaseGuardianEntity.GuardianState#IDLE}: в бою голова смотрит на
 * цель, и больше ни на что не отвлекается.
 *
 * <p>Пятый аргумент {@code onlyHorizontal} у ванильного гола сохранён: страж
 * следит за игроком, но голову держит ровно. Без этого флага голова
 * наклоняется вниз, т.к. глаза стража выше глаз игрока — выглядит как
 * «все смотрят в пол».
 */
public class GuardianLookAtPlayerGoal extends LookAtPlayerGoal {

    private final BaseGuardianEntity guardian;

    public GuardianLookAtPlayerGoal(BaseGuardianEntity guardian) {
        super(guardian, Player.class,
                (float) GuardianTuning.LOOK_AT_PLAYER_DISTANCE, 0.02F, true);
        this.guardian = guardian;
    }

    @Override
    public boolean canUse() {
        return this.guardian.getAIState() == BaseGuardianEntity.GuardianState.IDLE
                && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.guardian.getAIState() == BaseGuardianEntity.GuardianState.IDLE
                && super.canContinueToUse();
    }
}
