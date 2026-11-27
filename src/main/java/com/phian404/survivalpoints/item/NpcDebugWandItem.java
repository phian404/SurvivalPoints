package com.phian404.survivalpoints.item;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampFarmManager;
import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.entity.ai.FarmerStandWorkGoal;
import com.phian404.survivalpoints.entity.ai.CampWanderGoal;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskController;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
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
 *
 * <p>Информация разделена на три режима: идентификация (кто это и где
 * приписан), работа (занятость, задача, ферма, пути) и инвентарь
 * (экипировка и слоты). Shift+ПКМ в воздух переключает на следующий режим,
 * обычный ПКМ по NPC показывает данные текущего режима. Выбранный режим хранится
 * в NBT самого предмета, поэтому держится до следующего переключения.
 */
public class NpcDebugWandItem extends Item {

    private static final String MODE_TAG = "DebugWandMode";

    /** Категории диагностики: один режим — одна группа данных. */
    private enum Mode {
        IDENTITY("идентификация"),
        WORK("работа"),
        INVENTORY("инвентарь");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    public NpcDebugWandItem() {
        super(new Item.Properties());
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!(target instanceof BaseSurvivorEntity npc)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            // Без этого клиент считал, что клик по NPC не обработан, откатывался
            // к использованию предмета — и обычный ПКМ по NPC переключал режим.
            return InteractionResult.SUCCESS;
        }

        Mode mode = modeOf(stack);
        StringBuilder out = new StringBuilder("§e[NPC] §f")
                .append(nameOr(npc.getCustomName() == null ? null : npc.getCustomName().getString(), "—"))
                .append(" §8[").append(mode.label).append("]");

        Level level = player.level();
        UUID campId = npc.getCampId();
        CampRecord camp = campId == null || !(level instanceof ServerLevel serverLevel)
                ? null : CampManager.getCamp(serverLevel, campId);

        switch (mode) {
            case IDENTITY -> appendIdentity(out, npc, camp);
            case WORK -> appendWork(out, npc, camp, level);
            case INVENTORY -> appendInventory(out, npc);
        }

        out.append("\n §8Shift+ПКМ в воздух — следующий режим");
        player.displayClientMessage(Component.literal(out.toString()), false);
        return InteractionResult.SUCCESS;
    }

    /** Shift+ПКМ в воздух: переключить режим на следующий и показать какой включён. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        Mode mode = modeOf(stack).next();
        stack.getOrCreateTag().putInt(MODE_TAG, mode.ordinal());
        player.displayClientMessage(Component.literal("§e[NPC] §fрежим: ")
                .append(Component.literal(mode.label))
                .append(Component.literal("\n §8Shift+ПКМ в воздух — следующий режим")), false);
        return InteractionResultHolder.success(stack);
    }

    /** Кто это и где приписан. */
    private static void appendIdentity(StringBuilder out, BaseSurvivorEntity npc,
                                       @Nullable CampRecord camp) {
        out.append("\n §7Entity UUID: §f").append(npc.getUUID());
        out.append("\n §7identityId: §f").append(npc.getIdentityId());
        out.append("\n §7role: §f").append(npc.getRole().name());
        out.append("\n §7profession: §f").append(npc.getProfession().name());
        out.append("\n §7campId: §f").append(uuid(npc.getCampId()));
        out.append("\n §7campPostId: §f").append(uuid(npc.getCampPostId()));

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
    }

    /** Чем занят: рабочее место, задача, состояние фермы и пути. */
    private static void appendWork(StringBuilder out, BaseSurvivorEntity npc,
                                   @Nullable CampRecord camp, Level level) {
        out.append("\n §7workPoiPos: §f").append(pos(npc.getWorkPoiPos()));
        out.append("\n §7Занятость: §f").append(occupation(npc, camp, level));
        out.append("\n §7Задача: §f").append(taskState(npc));

        if (camp != null && level instanceof ServerLevel serverLevel
                && npc.getProfession() == SurvivorProfession.FARMER) {
            CampFarmManager farmManager = CampManager.peekFarmManager(camp);
            out.append("\n §7Ферма: §f")
                    .append(farmManager == null ? "manager=not_initialized"
                            : farmManager.diagnosticSummary(serverLevel.getGameTime()));
        }
        if (npc instanceof BaseSurvivorNpcEntity civilian) {
            out.append("\n §7Goals: §f").append(goalState(civilian));
        }
        if (npc.getNavigation().getPath() != null) {
            out.append("\n §7Путь: §f").append(npc.getNavigation().getPath().getNodeCount())
                    .append(" узлов, done=").append(npc.getNavigation().isDone());
        } else {
            out.append("\n §7Путь: §fнет");
        }
    }

    /** Экипировка в руке и содержимое инвентаря. */
    private static void appendInventory(StringBuilder out, BaseSurvivorEntity npc) {
        ItemStack mainHand = npc.getItemBySlot(EquipmentSlot.MAINHAND);
        out.append("\n §7экипировка (рука): §f");
        if (mainHand.isEmpty()) {
            out.append("§7пусто");
        } else {
            out.append(mainHand.getHoverName().getString())
                    .append(" §8×§f").append(mainHand.getCount());
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
    }

    private static Mode modeOf(ItemStack stack) {
        if (stack.getTag() == null) {
            return Mode.IDENTITY;
        }
        Mode[] all = Mode.values();
        return all[Math.floorMod(stack.getTag().getInt(MODE_TAG), all.length)];
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
                if (goal instanceof FarmerStandWorkGoal farmer) {
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
            if (goal instanceof FarmerStandWorkGoal work) {
                farmer = "FarmerWork=" + (wrapped.isRunning() ? "RUNNING" : "STOPPED")
                        + "/" + work.getState()
                        + " session=" + (work.isSessionActive() ? work.getSessionType() : "off")
                        + "/" + work.getSessionStarts()
                        + " stand=" + (work.getStand() == null ? "-" : pos(work.getStand()))
                        + " plan=" + work.getPlannedCount();
            } else if (goal instanceof CampWanderGoal campWander) {
                wander = "Wander=" + (wrapped.isRunning() ? "RUNNING" : "STOPPED")
                        + " [" + campWander.diagnosticSummary(npc.level().getGameTime()) + "]";
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
