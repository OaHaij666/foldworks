package com.pockethomestead.block;

import com.mojang.serialization.MapCodec;
import com.pockethomestead.blockentity.PickupChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// 取货箱 - 27格储物（类似木桶），绑定空间出口
// 放置在主世界，由调度器按速率从对应口袋空间出口方块添加产物
public class PickupChestBlock extends AbstractHomesteadBlock {
    public static final MapCodec<PickupChestBlock> CODEC = simpleCodec(PickupChestBlock::new);

    public PickupChestBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PickupChestBlockEntity(pos, state);
    }
}
