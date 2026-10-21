package com.phian404.survivalpoints.init;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Диагностическая команда /sp_guard.
 *
 * <p>Показывает по ближайшим стражам всё, что нужно для разбора поведения:
 * состояние ИИ, углы (курс тела/головы и наклон), высоту глаз, цель и расстояние,
 * активные голы, заряд арбалета, состояние опоры и коллизий, точку сбора.
 *
 * <p>Использование: /sp_guard [радиус]
 */
@Mod.EventBusSubscriber(modid = com.phian404.survivalpoints.SurvivalPointsMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardDebugCommand {

    private static final int DEFAULT_RADIUS = 24;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sp_guard")
                .requires(source -> source.hasPermission(0))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 128))
                        .executes(ctx -> report(ctx, IntegerArgumentType.getInteger(ctx, "radius"))))
                .executes(ctx -> report(ctx, DEFAULT_RADIUS)));
    }

    private static int report(CommandContext<CommandSourceStack> ctx, int radius) {
        CommandSourceStack source = ctx.getSource();

        Vec3 pos = source.getPosition();
        AABB area = new AABB(
                pos.x - radius, pos.y - radius, pos.z - radius,
                pos.x + radius, pos.y + radius, pos.z + radius);

        List<BaseGuardianEntity> guards = source.getLevel().getEntitiesOfClass(
                BaseGuardianEntity.class,
                area,
                e -> true);

        if (guards.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7[SP] Стражей в радиусе " + radius + " не найдено")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("§7[SP] Найдено стражей: " + guards.size())
                .withStyle(ChatFormatting.GRAY), false);

        for (int idx = 0; idx < guards.size(); idx++) {
            final int number = idx;
            final BaseGuardianEntity guard = guards.get(idx);
            source.sendSuccess(() -> Component.literal(describe(guard, number))
                    .withStyle(ChatFormatting.WHITE), false);
        }

        return guards.size();
    }

    private static String describe(BaseGuardianEntity guard, int index) {
        StringBuilder sb = new StringBuilder();

        sb.append("§e#").append(index + 1)
                .append(" §fstate=").append(guard.getAIState())
                .append(" §7hp=").append(Math.round(guard.getHealth()))
                .append('/').append(Math.round(guard.getMaxHealth()));

        // Цель
        LivingEntity target = guard.getTarget();
        if (target == null) {
            sb.append(" §7target=-");
        } else {
            sb.append(" §7target=").append(target.getType().getDescription().getString())
                    .append("§7@").append(String.format("%.1f", guard.distanceTo(target)));
        }

        // Углы: курс тела, курс головы, наклон головы
        sb.append("\n   §7rot: §fyaw=").append(String.format("%.1f", guard.getYRot()))
                .append(" §7headYaw=").append(String.format("%.1f", guard.yHeadRot))
                .append(" §7bodyYaw=").append(String.format("%.1f", guard.yBodyRot))
                .append(" §7pitch=").append(String.format("%.1f", guard.getXRot()));

        // Геометрия: от неё зависит наклон головы (глаза стража vs глаза игрока)
        sb.append(" §7eyeH=").append(String.format("%.3f", guard.getEyeHeight()))
                .append(" §7bbH=").append(String.format("%.2f", guard.getBbHeight()))
                .append(" §7type=").append(guard.getType().getDescription().getString());

        // Голы
        sb.append("\n   §7goals: §f").append(guard.getActiveGoalNames());

        // Разворот: какой курс должен быть, применяется ли он и что реально
        // уходит на клиент (syncYaw/syncPitch рисует рендерер).
        sb.append("\n   §7facing=§f").append(guard.shouldFaceTarget())
                .append(" §7wantYaw=").append(String.format("%.1f", guard.getDesiredYaw()))
                .append(" §7syncYaw=").append(String.format("%.1f", guard.getYRot()))
                .append(" §7syncHeadYaw=").append(String.format("%.1f", guard.getSyncedHeadYaw()))
                .append(" §7syncPitch=").append(String.format("%.1f", guard.getSyncedHeadPitch()));

        // Наведение: насколько корпус отличается от направления на цель.
        // Пока |aimErr| больше допуска, страж не стреляет (aligned=false).
        LivingEntity aimTarget = guard.getTarget();
        float aimError = aimTarget != null ? guard.getBodyYawError(aimTarget) : Float.NaN;
        sb.append("\n   §7aimErr=").append(String.format("%.1f", aimError))
                .append(" §7aligned=§f").append(aimTarget != null
                        && guard.isBodyAlignedWithTarget(aimTarget));

        // Статистика стрельбы: shots — выстрелено, hits — попаданий (сервер),
        // dmg — нанесённый урон. Если hits растёт, а dmg нет — урон не проходит.
        sb.append("\n   §7shots=").append(guard.getShotsFired())
                .append(" §7hits=").append(guard.getArrowsHit())
                .append(" §7dmg=").append(String.format("%.1f", guard.getDamageDealt()));

        // Состояние перемещения
        sb.append("\n   §7charge=").append(guard.getCrossbowChargeTime())
                .append(" §7shotDelay=").append(guard.getShotDelay())
                .append(" §7onGround=").append(guard.onGround())
                .append(" §7hColl=").append(guard.horizontalCollision)
                .append(" §7water=").append(guard.isInWater());

        // Навигация: есть ли маршрут и куда он ведёт
        boolean navDone = guard.getNavigation().isDone();
        sb.append(" §7nav=").append(navDone ? "нет" : "есть")
                .append(" §7navTo=").append(String.format("%.1f/%.1f",
                        guard.getMoveControl().getWantedX(),
                        guard.getMoveControl().getWantedZ()));

        // Точка сбора
        if (guard.hasRallyPoint() && guard.getRallyPoint() != null) {
            sb.append("\n   §7rally: §f").append(guard.getRallyPoint().toShortString());
        } else {
            sb.append("\n   §7rally: §7none");
        }

        return sb.toString();
    }
}
