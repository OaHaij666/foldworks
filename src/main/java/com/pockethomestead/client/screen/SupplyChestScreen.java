package com.pockethomestead.client.screen;

import com.pockethomestead.menu.SupplyChestMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 供货箱Screen
 */
public class SupplyChestScreen extends BaseChestScreen<SupplyChestMenu> {

    public SupplyChestScreen(SupplyChestMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }
}
