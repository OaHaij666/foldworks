package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.menu.HomesteadChestMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(
            net.minecraft.core.registries.Registries.MENU, PocketHomestead.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<HomesteadChestMenu>> HOMESTEAD_CHEST =
            MENU_TYPES.register("homestead_chest", () -> IMenuTypeExtension.create((containerId, playerInventory, data) -> {
                BlockPos pos = data.readBlockPos();
                BaseChestBlockEntity be = HomesteadChestAccess.resolve(playerInventory.player.level().getBlockEntity(pos));
                return new HomesteadChestMenu(containerId, playerInventory, be);
            }));
}
