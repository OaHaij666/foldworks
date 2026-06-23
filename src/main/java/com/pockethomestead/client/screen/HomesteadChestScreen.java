package com.pockethomestead.client.screen;

import com.pockethomestead.menu.HomesteadChestMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class HomesteadChestScreen extends BaseChestScreen<HomesteadChestMenu> {
    public HomesteadChestScreen(HomesteadChestMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }
}
