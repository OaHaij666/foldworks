package com.foldworks.menu;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.registration.ModMenuTypes;
import net.minecraft.world.entity.player.Inventory;

public class FoldworksChestMenu extends BaseChestMenu {
    public FoldworksChestMenu(int containerId, Inventory playerInventory, BaseChestBlockEntity blockEntity) {
        super(ModMenuTypes.FOLDWORKS_CHEST.get(), containerId, playerInventory, blockEntity);
    }

    @Override
    public BaseChestBlockEntity getBlockEntity() {
        return blockEntity;
    }
}
