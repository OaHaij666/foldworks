package com.pockethomestead.block;

import com.mojang.serialization.MapCodec;
import com.pockethomestead.blockentity.SupplyChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// 供货箱 - 27格储物（类似木桶），绑定空间入口
// 放置在主世界，放入原料后由调度器传入对应口袋空间的入口方块
public class SupplyChestBlock extends AbstractHomesteadBlock {
    public static final MapCodec<SupplyChestBlock> CODEC = simpleCodec(SupplyChestBlock::new);

    public SupplyChestBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SupplyChestBlockEntity(pos, state);
    }
}
