package com.pockethomestead.blockentity;

import net.minecraft.world.level.block.entity.BlockEntity;

public interface HomesteadChestAccess {
    BaseChestBlockEntity homesteadChest();

    static BaseChestBlockEntity resolve(BlockEntity blockEntity) {
        if (blockEntity instanceof BaseChestBlockEntity chest) return chest;
        if (blockEntity instanceof HomesteadChestAccess access) return access.homesteadChest();
        return null;
    }
}
