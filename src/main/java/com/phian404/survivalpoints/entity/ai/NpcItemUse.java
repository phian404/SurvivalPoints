package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * Видимое использование предмета NPC: что лежит в главной руке и когда NPC
 * делает жест этим предметом.
 *
 * <p>Предмет в руке уходит клиентам ванильным пакетом экипировки
 * ({@code setItemSlot}), замах — пакетом анимации ({@code swing}), поэтому
 * остальные игроки видят и предмет, и движение без отдельной синхронизации.
 *
 * <p>Класс намеренно ничего не знает ни о профессиях, ни о конкретных
 * действиях: какая цель — такой предмет и звук. Огниво подключается так же,
 * как семя: {@link #takeFromInventory}, {@link #hold}, а в момент поджига
 * {@link #act} со звуком {@code SoundEvents.FLINTANDSTEEL_USE}.
 */
public final class NpcItemUse {

    private NpcItemUse() {
    }

    /**
     * Взять в главную руку первый подходящий предмет из внутреннего инвентаря.
     *
     * @return {@code true}, если предмет действительно взят
     */
    public static boolean takeFromInventory(BaseSurvivorEntity npc, Predicate<ItemStack> filter) {
        for (int slot = 0; slot < npc.getInventory().getSlots(); slot++) {
            ItemStack stack = npc.getInventory().getStackInSlot(slot);
            if (stack.isEmpty() || !filter.test(stack)) {
                continue;
            }
            ItemStack extracted = npc.extractItem(stack, 1);
            if (extracted.isEmpty()) {
                continue;
            }
            npc.setItemSlot(EquipmentSlot.MAINHAND, extracted);
            return true;
        }
        return false;
    }

    /** Положить предмет в главную руку; пустой стек освобождает руку. */
    public static void hold(LivingEntity npc, ItemStack stack) {
        npc.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    /** Замах рукой и необязательный звук — «NPC сделал действие предметом». */
    public static void act(LivingEntity npc, @Nullable SoundEvent sound, float volume) {
        npc.swing(InteractionHand.MAIN_HAND);
        if (sound != null) {
            npc.playSound(sound, volume, 1.0F);
        }
    }
}
