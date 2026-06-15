package com.pockethomestead.menu;

import com.pockethomestead.blockentity.SupplyChestBlockEntity;
import com.pockethomestead.registration.ModMenuTypes;
import net.minecraft.world.entity.player.Inventory;

/**
 * 供货箱Menu
 */
public class SupplyChestMenu extends BaseChestMenu {

    public SupplyChestMenu(int containerId, Inventory playerInventory, SupplyChestBlockEntity blockEntity) {
        super(ModMenuTypes.SUPPLY_CHEST.get(), containerId, playerInventory, blockEntity);
    }

    @Override
    public SupplyChestBlockEntity getBlockEntity() {
        return (SupplyChestBlockEntity) blockEntity;
    }
}
