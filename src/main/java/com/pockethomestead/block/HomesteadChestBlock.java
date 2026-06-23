package com.pockethomestead.block;

import com.mojang.serialization.MapCodec;
import com.pockethomestead.blockentity.HomesteadChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class HomesteadChestBlock extends AbstractHomesteadBlock {
    public static final MapCodec<HomesteadChestBlock> CODEC = simpleCodec(HomesteadChestBlock::new);

    public HomesteadChestBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HomesteadChestBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HomesteadChestBlockEntity(pos, state);
    }
}
