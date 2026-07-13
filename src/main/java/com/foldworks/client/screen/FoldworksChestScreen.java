package com.foldworks.client.screen;

import com.foldworks.menu.FoldworksChestMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class FoldworksChestScreen extends BaseChestScreen<FoldworksChestMenu> {
    public FoldworksChestScreen(FoldworksChestMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }
}
