package com.pockethomestead.blockentity;

import com.pockethomestead.menu.PickupChestMenu;
import com.pockethomestead.network.ChestConfigPacket;
import com.pockethomestead.registry.ChestRegistryManager;
import com.pockethomestead.registration.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.state.BlockState;

// 取货箱 BlockEntity - 支持绑定到供货箱并接收物品
public class PickupChestBlockEntity extends BaseChestBlockEntity {

    public PickupChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PICKUP_CHEST.get(), pos, state);
    }

    @Override
    public ChestRegistryManager.ChestType getChestType() {
        return ChestRegistryManager.ChestType.PICKUP;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        PickupChestMenu menu = new PickupChestMenu(containerId, playerInventory, this);
        if (player instanceof ServerPlayer sp) {
            ChestConfigPacket.sendSyncToClient(sp, this);
        }
        return menu;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.pockethomestead.pickup_chest");
    }
}
