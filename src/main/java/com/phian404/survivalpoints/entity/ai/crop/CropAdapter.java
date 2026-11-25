package com.phian404.survivalpoints.entity.ai.crop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Адаптер культуры для фермера.
 *
 * <p>Отделяет поведение {@code FarmerWorkGoal} от конкретной реализации
 * растения. Адаптер работает с блоком культуры, который стоит на пашни POI.
 * Для будущих культур (включая модовые) достаточно зарегистрировать новый
 * адаптер — сам Goal не меняется.
 */
public interface CropAdapter {

    /** Адаптеры в порядке приоритета; первый подходящий используется. */
    List<CropAdapter> REGISTRY = new ArrayList<>(List.of(CropBlockAdapter.INSTANCE));

    /** Зарегистрировать адаптер. Повторная регистрация игнорируется. */
    static void register(CropAdapter adapter) {
        if (!REGISTRY.contains(adapter)) {
            REGISTRY.add(adapter);
        }
    }

    /** Найти адаптер для блока культуры; {@code null}, если культура не поддерживается. */
    @Nullable
    static CropAdapter find(BlockState state) {
        for (CropAdapter adapter : REGISTRY) {
            if (adapter.supports(state)) {
                return adapter;
            }
        }
        return null;
    }

    /** Поддерживает ли адаптер этот блок. */
    boolean supports(BlockState state);

    /** Достигла ли культура зрелости. */
    boolean isMature(BlockState state);

    /**
     * Предмет для посадки или {@code null}, если адаптер не может его определить.
     *
     * <p>Ограничение конкретной культуры изолировано здесь, а не в Goal.
     */
    @Nullable
    ItemStack getSeed(ServerLevel level, BlockPos pos, BlockState state);

    /**
     * Результат сбора: список стеков, которые нужно добавить в инвентарь.
     *
     * <p>Вызывается до разрушения блока — Goal сначала проверяет, что всё
     * помещается в инвентарь.
     */
    List<ItemStack> getDrops(ServerLevel level, BlockPos pos, BlockState state);

    /** Посадить культуру на блоке. Возвращает {@code true} при успехе. */
    boolean plant(ServerLevel level, BlockPos pos);
}
