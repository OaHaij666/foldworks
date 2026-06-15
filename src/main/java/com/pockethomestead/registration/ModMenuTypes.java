package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.blockentity.PickupChestBlockEntity;
import com.pockethomestead.blockentity.SupplyChestBlockEntity;
import com.pockethomestead.menu.PickupChestMenu;
import com.pockethomestead.menu.SupplyChestMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(
            net.minecraft.core.registries.Registries.MENU, PocketHomestead.MODID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<MenuType<?>, MenuType<SupplyChestMenu>> SUPPLY_CHEST =
            MENU_TYPES.register("supply_chest", () -> IMenuTypeExtension.create((containerId, playerInventory, data) -> {
                BlockPos pos = data.readBlockPos();
                SupplyChestBlockEntity be = (SupplyChestBlockEntity) playerInventory.player.level().getBlockEntity(pos);
                return new SupplyChestMenu(containerId, playerInventory, be);
            }));

    public static final net.neoforged.neoforge.registries.DeferredHolder<MenuType<?>, MenuType<PickupChestMenu>> PICKUP_CHEST =
            MENU_TYPES.register("pickup_chest", () -> IMenuTypeExtension.create((containerId, playerInventory, data) -> {
                BlockPos pos = data.readBlockPos();
                PickupChestBlockEntity be = (PickupChestBlockEntity) playerInventory.player.level().getBlockEntity(pos);
                return new PickupChestMenu(containerId, playerInventory, be);
            }));
}

