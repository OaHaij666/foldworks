package com.pockethomestead.client.screen;

import com.pockethomestead.menu.PickupChestMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 取货箱Screen
 */
public class PickupChestScreen extends BaseChestScreen<PickupChestMenu> {

    public PickupChestScreen(PickupChestMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }
}
