package com.pockethomestead.compat.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class CreateStressHooks {
    private CreateStressHooks() {
    }

    public static void updateStressAxis(Level level, BlockPos pos, Direction.Axis axis) {
        if (level == null || pos == null || axis == null) return;
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(BlockStateProperties.AXIS)) return;
        if (state.getValue(BlockStateProperties.AXIS) == axis) return;
        level.setBlock(pos, state.setValue(BlockStateProperties.AXIS, axis), 3);
    }
}
