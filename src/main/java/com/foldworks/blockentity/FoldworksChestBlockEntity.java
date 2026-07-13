package com.foldworks.blockentity;

import com.foldworks.menu.FoldworksChestMenu;
import com.foldworks.registration.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

public class FoldworksChestBlockEntity extends BaseChestBlockEntity {
    public FoldworksChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FOLDWORKS_CHEST.get(), pos, state);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        FoldworksChestMenu menu = new FoldworksChestMenu(containerId, playerInventory, this);
        menu.addSlotListener(new net.minecraft.world.inventory.ContainerListener() {
            @Override public void slotChanged(AbstractContainerMenu container, int slot, net.minecraft.world.item.ItemStack stack) {}
            @Override public void dataChanged(AbstractContainerMenu container, int id, int value) {}
        });
        return menu;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.foldworks.foldworks_chest");
    }
}
