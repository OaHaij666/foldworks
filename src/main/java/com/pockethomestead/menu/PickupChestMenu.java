package com.pockethomestead.menu;

import com.pockethomestead.blockentity.PickupChestBlockEntity;
import com.pockethomestead.registration.ModMenuTypes;
import net.minecraft.world.entity.player.Inventory;

/**
 * 取货箱Menu
 */
public class PickupChestMenu extends BaseChestMenu {

    public PickupChestMenu(int containerId, Inventory playerInventory, PickupChestBlockEntity blockEntity) {
        super(ModMenuTypes.PICKUP_CHEST.get(), containerId, playerInventory, blockEntity);
    }

    @Override
    public PickupChestBlockEntity getBlockEntity() {
        return (PickupChestBlockEntity) blockEntity;
    }
}
