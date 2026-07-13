package com.foldworks.block;

import com.mojang.serialization.MapCodec;
import com.foldworks.blockentity.FoldworksChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class FoldworksChestBlock extends AbstractFoldworksBlock {
    public static final MapCodec<FoldworksChestBlock> CODEC = simpleCodec(FoldworksChestBlock::new);

    public FoldworksChestBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends FoldworksChestBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FoldworksChestBlockEntity(pos, state);
    }
}
