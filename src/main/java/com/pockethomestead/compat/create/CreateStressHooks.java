package com.pockethomestead.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

public final class CreateStressHooks {
    private static final String CREATE_CHEST_BE = "com.pockethomestead.compat.create.CreateHomesteadChestBlockEntity";

    private CreateStressHooks() {
    }

    public static void updateStressAxis(Level level, BlockPos pos, Direction.Axis axis) {
        if (level == null || pos == null || axis == null) return;
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(BlockStateProperties.AXIS)) {
            refreshStressKinetics(level, pos);
            return;
        }
        if (state.getValue(BlockStateProperties.AXIS) != axis) {
            level.setBlock(pos, state.setValue(BlockStateProperties.AXIS, axis), 3);
        }
        refreshStressKinetics(level, pos);
    }

    public static void refreshStressKinetics(Level level, BlockPos pos) {
        if (level == null || pos == null || level.isClientSide || !ModList.get().isLoaded("create")) return;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !CREATE_CHEST_BE.equals(blockEntity.getClass().getName())) return;
        try {
            Method method = blockEntity.getClass().getMethod("refreshKineticStateFromConfig");
            method.invoke(blockEntity);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public static boolean syncClientData(Level level, BlockPos pos) {
        if (level == null || pos == null || level.isClientSide || !ModList.get().isLoaded("create")) return false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null || !CREATE_CHEST_BE.equals(blockEntity.getClass().getName())) return false;
        try {
            Method method = blockEntity.getClass().getMethod("sendData");
            method.invoke(blockEntity);
            return true;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
