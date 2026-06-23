package com.pockethomestead.menu;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.registration.ModMenuTypes;
import net.minecraft.world.entity.player.Inventory;

public class HomesteadChestMenu extends BaseChestMenu {
    public HomesteadChestMenu(int containerId, Inventory playerInventory, BaseChestBlockEntity blockEntity) {
        super(ModMenuTypes.HOMESTEAD_CHEST.get(), containerId, playerInventory, blockEntity);
    }

    @Override
    public BaseChestBlockEntity getBlockEntity() {
        return blockEntity;
    }
}
