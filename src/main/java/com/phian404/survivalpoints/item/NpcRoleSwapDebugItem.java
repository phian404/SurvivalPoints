package com.phian404.survivalpoints.item;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.role.RoleAssignmentService;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * ВРЕМЕННЫЙ отладочный предмет для проверки переноса инвентаря при смене роли.
 *
 * <p>ПКМ по NPC переключает роль в обе стороны: CIVILIAN → GUARDIAN и обратно.
 * Не регистрируется в творческом инвентаре — выдаётся только командой
 * {@code /give}. Не является игровой механикой и должен быть удалён после
 * тестирования.
 */
public class NpcRoleSwapDebugItem extends Item {

    public NpcRoleSwapDebugItem() {
        super(new Item.Properties());
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide || !(target instanceof BaseSurvivorEntity npc)) {
            return InteractionResult.PASS;
        }

        SurvivorRole targetRole = npc instanceof BaseGuardianEntity
                ? SurvivorRole.CIVILIAN : SurvivorRole.GUARDIAN;
        BaseSurvivorEntity replacement = RoleAssignmentService.reassign(npc, targetRole);
        if (replacement == null) {
            player.displayClientMessage(Component.literal("§c[SP] Не удалось сменить роль NPC"), false);
            return InteractionResult.PASS;
        }
        if (targetRole == SurvivorRole.GUARDIAN) {
            RoleAssignmentService.prepareGuardianEquipment(replacement);
        }
        player.displayClientMessage(Component.literal("§a[SP] Роль изменена: §f"
                + targetRole.name()), false);
        return InteractionResult.SUCCESS;
    }
}
