package com.pockethomestead.blockentity;

import com.pockethomestead.menu.HomesteadChestMenu;
import com.pockethomestead.registration.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

public class HomesteadChestBlockEntity extends BaseChestBlockEntity {
    public HomesteadChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HOMESTEAD_CHEST.get(), pos, state);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        HomesteadChestMenu menu = new HomesteadChestMenu(containerId, playerInventory, this);
        menu.addSlotListener(new net.minecraft.world.inventory.ContainerListener() {
            @Override public void slotChanged(AbstractContainerMenu container, int slot, net.minecraft.world.item.ItemStack stack) {}
            @Override public void dataChanged(AbstractContainerMenu container, int id, int value) {}
        });
        return menu;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.pockethomestead.homestead_chest");
    }
}
