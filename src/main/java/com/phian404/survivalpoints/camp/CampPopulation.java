package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.role.RoleAssignmentService;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Создание населения лагеря сразу после застройки и расстановки постов.
 *
 * <p>Вызывается только из {@link CampManager#createCamp}: при загрузке мира
 * население не восстанавливается — живые NPC сохраняются штатными средствами
 * Minecraft, погибшие не заменяются.
 *
 * <p>Частичный успех допустим: каждый житель добавляется в
 * {@link CampRecord#getResidents()} только после успешного {@code addFreshEntity}.
 * Флаг {@code populationInitialized} выставляется после завершения цикла,
 * даже если часть жителей не была создана, — это исключает дубликаты.
 */
public final class CampPopulation {

    /** Радиус спирали поиска безопасной позиции вокруг базовой клетки. */
    private static final int SEARCH_RADIUS = 3;

    /** Минимальная дистанция между NPC в блоках. */
    private static final double MIN_NPC_DISTANCE = 2.0D;

    private CampPopulation() {
    }

    /**
     * Создать население лагеря.
     *
     * @param level  серверный уровень
     * @param record лагерь, уже записанный в {@link CampSavedData} и застроенный
     * @param plan   план застройки
     */
    public static void spawn(ServerLevel level, CampRecord record, CampSitePlanner.Plan plan) {
        if (record.isPopulationInitialized()) {
            return;
        }

        int radius = plan.getRadius();
        List<BlockPos> placed = new ArrayList<>();

        // 1. Лидер — обычный CIVILIAN, лидерство живёт только в CampRecord.
        spawnResident(level, record, plan, new LocalCell(radius - 2, radius + 2),
                SurvivorRole.CIVILIAN, SurvivorProfession.NONE, null, null, placed, true);

        // 2. Фермер — CIVILIAN с профессией FARMER и POI пашни (если есть).
        BlockPos farmPoi = findFarmPoi(record);
        spawnResident(level, record, plan, new LocalCell(radius * 2 - 7, 10),
                SurvivorRole.CIVILIAN, SurvivorProfession.FARMER, null, farmPoi, placed, false);

        // 3. Страж — GUARDIAN на свободном посту. Пост ищется до создания,
        //    чтобы не создавать стража без назначения.
        CampPost post = record.getFreePost();
        if (post != null) {
            spawnResident(level, record, plan, new LocalCell(radius, radius * 2 - 1),
                    SurvivorRole.GUARDIAN, SurvivorProfession.NONE, post, null, placed, false);
        }

        // 4. Обычные выжившие: 1–2, в зависимости от удачи.
        int survivors = 1 + level.getRandom().nextInt(2);
        LocalCell[] survivorCells = {
                new LocalCell(radius - 3, radius - 3),
                new LocalCell(radius - 2, radius + 5)
        };
        for (int i = 0; i < survivors; i++) {
            spawnResident(level, record, plan, survivorCells[i],
                    SurvivorRole.CIVILIAN, SurvivorProfession.NONE, null, null, placed, false);
        }

        // Флаг — только после полного цикла. Частичный успех тоже считается
        // завершённой генерацией: повторный спавн создал бы дубликатов.
        record.setPopulationInitialized(true);
    }

    /**
     * Создать одного жителя. Возврат без действия при отсутствии позиции,
     * поста или при отказе {@code addFreshEntity}.
     */
    private static void spawnResident(ServerLevel level, CampRecord record, CampSitePlanner.Plan plan,
                                      LocalCell base, SurvivorRole role, SurvivorProfession profession,
                                      @Nullable CampPost post, @Nullable BlockPos workPoiPos,
                                      List<BlockPos> placed, boolean leader) {
        BlockPos spawn = findSafePos(level, record, plan, base, placed);
        if (spawn == null) {
            return;
        }

        BaseSurvivorEntity entity = RoleAssignmentService.create(level, role, record.getId(),
                post == null ? null : post.getId());
        if (entity == null) {
            return;
        }

        entity.setProfession(profession);
        entity.setWorkPoiPos(workPoiPos);
        entity.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                plan.getGateFacing().toYRot(), 0.0F);
        entity.setPersistenceRequired();
        if (entity instanceof BaseGuardianEntity) {
            RoleAssignmentService.prepareGuardianEquipment(entity);
        }

        if (!level.addFreshEntity(entity)) {
            entity.discard();
            return;
        }

        UUID identity = entity.getIdentityId();
        record.addResident(identity);
        if (post != null) {
            record.assignGuardian(identity, post);
        }
        if (leader) {
            record.setLeaderIdentityId(identity);
        }
        placed.add(spawn);
    }

    /**
     * Безопасная позиция: базовая клетка, затем спираль радиуса
     * {@link #SEARCH_RADIUS}. Позиция проверяется по блокам, слотам, POI и
     * расстоянию до уже размещённых жителей.
     */
    @Nullable
    private static BlockPos findSafePos(ServerLevel level, CampRecord record,
                                        CampSitePlanner.Plan plan, LocalCell base,
                                        List<BlockPos> placed) {
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int lx = base.x() + dx;
                int lz = base.z() + dz;
                if (lx < 1 || lz < 1 || lx >= plan.getSide() - 1 || lz >= plan.getSide() - 1) {
                    continue;
                }
                BlockPos pos = plan.localToWorld(lx, lz, plan.getFloorY());
                if (isSafe(level, record, pos, placed)) {
                    return pos;
                }
            }
        }
        return null;
    }

    /** Проверка одной позиции по правилам §4 плана. */
    private static boolean isSafe(ServerLevel level, CampRecord record, BlockPos pos,
                                  List<BlockPos> placed) {
        BlockState feet = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        if (feet.blocksMotion() || head.blocksMotion()
                || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) {
            return false;
        }
        if (!level.getBlockState(pos.below()).blocksMotion()) {
            return false;
        }
        for (CampModuleSlot slot : record.getModuleSlots().values()) {
            if (slot.getBounds().contains(pos)) {
                return false;
            }
        }
        for (CampPoi poi : record.getPois()) {
            if (poi.getPos().equals(pos)) {
                return false;
            }
        }
        for (BlockPos other : placed) {
            if (other.distSqr(pos) < MIN_NPC_DISTANCE * MIN_NPC_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    /** Первая POI пашни лагеря или {@code null}, если её нет. */
    @Nullable
    private static BlockPos findFarmPoi(CampRecord record) {
        for (CampPoi poi : record.getPois()) {
            if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())) {
                return poi.getPos();
            }
        }
        return null;
    }

    /** Локальная клетка планировки. */
    private record LocalCell(int x, int z) {
    }
}
