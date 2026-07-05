package com.pockethomestead.moving;

import com.pockethomestead.blockentity.HomesteadChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

final class MovingHomesteadChestBlockEntity extends HomesteadChestBlockEntity {
    MovingHomesteadChestBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public void registerIfReady() {
        // 运动中的传输箱没有真实世界方块，位置解析由 MovingChestRegistry 接管。
    }
}
