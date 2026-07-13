package com.foldworks.blockentity;

import net.minecraft.world.level.block.entity.BlockEntity;

public interface FoldworksChestAccess {
    BaseChestBlockEntity foldworksChest();

    static BaseChestBlockEntity resolve(BlockEntity blockEntity) {
        if (blockEntity instanceof BaseChestBlockEntity chest) return chest;
        if (blockEntity instanceof FoldworksChestAccess access) return access.foldworksChest();
        return null;
    }
}
