package com.foldworks.moving;

import com.foldworks.blockentity.FoldworksChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

final class MovingFoldworksChestBlockEntity extends FoldworksChestBlockEntity {
    MovingFoldworksChestBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public void registerIfReady() {
        // 运动中的维度仓没有真实世界方块，位置解析由 MovingChestRegistry 接管。
    }
}
