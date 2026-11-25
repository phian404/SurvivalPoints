package com.phian404.survivalpoints.item;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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

        UUID campId = npc.getCampId();
        CampRecord camp = campId == null || !(player.level() instanceof ServerLevel serverLevel)
                ? null : CampManager.getCamp(serverLevel, campId);
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
}
