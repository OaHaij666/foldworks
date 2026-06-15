package com.pockethomestead.blockentity;

import com.pockethomestead.menu.SupplyChestMenu;
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

// 供货箱 BlockEntity - 支持绑定到取货箱并传输物品
public class SupplyChestBlockEntity extends BaseChestBlockEntity {

    public SupplyChestBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SUPPLY_CHEST.get(), pos, state);
    }

    @Override
    public ChestRegistryManager.ChestType getChestType() {
        return ChestRegistryManager.ChestType.SUPPLY;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        SupplyChestMenu menu = new SupplyChestMenu(containerId, playerInventory, this);
        if (player instanceof ServerPlayer sp) {
            ChestConfigPacket.sendSyncToClient(sp, this);
        }
        return menu;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.pockethomestead.supply_chest");
    }
}
