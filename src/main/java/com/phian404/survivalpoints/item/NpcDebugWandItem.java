package com.phian404.survivalpoints.item;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.entity.ai.FarmerWorkGoal;
import com.phian404.survivalpoints.entity.ai.CampWanderGoal;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskController;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Отладочная палочка для просмотра данных NPC.
 *
 * <p>Не игровая механика: предмет только читает состояние и пишет его в чат.
 * Не расходуется, не наносит урон и не меняет данные сущности. Для других
 * предметов взаимодействие остаётся прежним — перехват идёт только когда
 * в руке именно этот предмет.
 */
public class NpcDebugWandItem extends Item {

    public NpcDebugWandItem() {
        super(new Item.Properties());
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide || !(target instanceof BaseSurvivorEntity npc)) {
            return InteractionResult.PASS;
        }

        StringBuilder out = new StringBuilder("§e[NPC] §f");
        out.append(nameOr(npc.getCustomName() == null ? null : npc.getCustomName().getString(), "—"));
        out.append("\n §7Entity UUID: §f").append(npc.getUUID());
        out.append("\n §7identityId: §f").append(npc.getIdentityId());
        out.append("\n §7campId: §f").append(uuid(npc.getCampId()));
        out.append("\n §7role: §f").append(npc.getRole().name());
        out.append("\n §7profession: §f").append(npc.getProfession().name());
        out.append("\n §7campPostId: §f").append(uuid(npc.getCampPostId()));
        out.append("\n §7workPoiPos: §f").append(pos(npc.getWorkPoiPos()));

        ItemStack mainHand = npc.getItemBySlot(EquipmentSlot.MAINHAND);
        out.append("\n §7экипировка (рука): §f");
        if (mainHand.isEmpty()) {
            out.append("§7пусто");
        } else {
            out.append(mainHand.getHoverName().getString())
                    .append(" §8×§f").append(mainHand.getCount());
        }

        UUID campId = npc.getCampId();
        CampRecord camp = campId == null || !(player.level() instanceof ServerLevel serverLevel)
                ? null : CampManager.getCamp(serverLevel, campId);

        out.append("\n §7Занятость: §f").append(occupation(npc, camp, player.level()));
        out.append("\n §7Задача: §f").append(taskState(npc));

        if (camp != null && player.level() instanceof ServerLevel serverLevel
                && npc.getProfession() == SurvivorProfession.FARMER) {
            out.append("\\n §7Ферма: §f")
                    .append(CampManager.farmManager(camp).diagnosticSummary(serverLevel.getGameTime()));
        }
        if (npc instanceof BaseSurvivorNpcEntity civilian) {
            out.append("\\n §7Goals: §f").append(goalState(civilian));
        }
        if (npc.getNavigation().getPath() != null) {
            out.append("\\n §7Путь: §f").append(npc.getNavigation().getPath().getNodeCount())
                    .append(" узлов, done=").append(npc.getNavigation().isDone());
        } else {
            out.append("\\n §7Путь: §fнет");
        }

        if (camp != null) {
            UUID identity = npc.getIdentityId();
            boolean leader = identity.equals(camp.getLeaderIdentityId());
            out.append("\n §7лагерь: §f").append(camp.getName())
                    .append(" §7(").append(camp.getShortId()).append(")");
            out.append("\n §7лидер: §f").append(leader ? "да" : "нет");
            out.append("\n §7в residents: §f")
                    .append(camp.getResidents().contains(identity) ? "да" : "нет");
        } else {
            out.append("\n §7лагерь: §fнет или не найден");
        }

        out.append("\n §7Инвентарь:");
        for (int slot = 0; slot < npc.getInventory().getSlots(); slot++) {
            ItemStack stored = npc.getInventory().getStackInSlot(slot);
            out.append("\n §f").append(slot + 1).append(". ");
            if (stored.isEmpty()) {
                out.append("§7пусто");
            } else {
                out.append(stored.getHoverName().getString())
                        .append(" §8×§f").append(stored.getCount());
            }
        }

        player.displayClientMessage(Component.literal(out.toString()), false);
        return InteractionResult.SUCCESS;
    }

    private static String nameOr(@Nullable String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String uuid(@Nullable UUID value) {
        return value == null ? "—" : value.toString();
    }

    private static String pos(@Nullable BlockPos value) {
        return value == null ? "—" : value.toShortString();
    }

    /** Текущая деятельность NPC из контроллера и исполнительного Goal. */
    private static String taskState(BaseSurvivorEntity npc) {
        NpcTaskController controller = CampManager.peekTaskController(npc.getIdentityId());
        NpcTask task = controller == null ? null : controller.current();
        if (task == null) {
            return "REST → IDLE";
        }
        String state = "IDLE";
        if (npc instanceof BaseSurvivorNpcEntity civilian) {
            for (net.minecraft.world.entity.ai.goal.Goal goal
                    : civilian.goalSelector.getRunningGoals()
                            .map(wrapped -> wrapped.getGoal()).toList()) {
                if (goal instanceof FarmerWorkGoal farmer) {
                    state = farmer.getState().name();
                    break;
                }
            }
        }
        StringBuilder out = new StringBuilder(task.getPriority().name())
                .append(" → ").append(task.getType())
                .append(" → ").append(state);
        if (task.getTarget() != null) {
            out.append(" → ").append(pos(task.getTarget()));
        }
        return out.toString();
    }

    /** Состояние зарегистрированных AI Goal, включая не запущенные. */
    private static String goalState(BaseSurvivorNpcEntity npc) {
        String farmer = "FarmerWork=missing";
        String wander = "Wander=missing";
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped
                : npc.goalSelector.getAvailableGoals()) {
            net.minecraft.world.entity.ai.goal.Goal goal = wrapped.getGoal();
            if (goal instanceof FarmerWorkGoal work) {
                farmer = "FarmerWork=" + (wrapped.isRunning() ? "RUNNING" : "STOPPED")
                        + "/" + work.getState();
            } else if (goal instanceof CampWanderGoal) {
                wander = "Wander=" + (wrapped.isRunning() ? "RUNNING" : "STOPPED");
            }
        }
        return farmer + ", " + wander;
    }

    /**
     * Текст занятости NPC по профессии и доступности рабочего места.
     *
     * <p>Профессия не сбрасывается: при недоступном месте показывается
     * «фермерство -> без занятия», а не смена профессии.
     */
    private static String occupation(BaseSurvivorEntity npc, @Nullable CampRecord camp, Level level) {
        if (npc.getProfession() == SurvivorProfession.NONE) {
            return "без занятия";
        }
        if (npc.getProfession() != SurvivorProfession.FARMER) {
            return "без занятия";
        }
        return hasFarmWorkplace(npc, camp, level) ? "фермерство" : "фермерство -> без занятия";
    }

    /**
     * Доступность фермерского рабочего места.
     *
     * <p>Точка должна быть задана и присутствовать в POI лагеря с типом
     * {@code farm_plot}. Если чанк загружен, дополнительно проверяется пашня
     * по координатам. Выгруженный чанк не считается потерей места.
     */
    private static boolean hasFarmWorkplace(BaseSurvivorEntity npc, @Nullable CampRecord camp,
                                            Level level) {
        BlockPos workPos = npc.getWorkPoiPos();
        if (workPos == null || camp == null) {
            return false;
        }

        boolean poiExists = false;
        for (CampPoi poi : camp.getPois()) {
            if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())
                    && workPos.equals(poi.getPos())) {
                poiExists = true;
                break;
            }
        }
        if (!poiExists) {
            return false;
        }

        if (level.isLoaded(workPos) && !level.getBlockState(workPos).is(Blocks.FARMLAND)) {
            return false;
        }
        return true;
    }
}
