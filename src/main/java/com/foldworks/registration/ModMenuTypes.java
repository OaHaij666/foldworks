package com.foldworks.registration;

import com.foldworks.Foldworks;
import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.foldworks.menu.FoldworksChestMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(
            net.minecraft.core.registries.Registries.MENU, Foldworks.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<FoldworksChestMenu>> FOLDWORKS_CHEST =
            MENU_TYPES.register("foldworks_chest", () -> IMenuTypeExtension.create((containerId, playerInventory, data) -> {
                BlockPos pos = data.readBlockPos();
                BaseChestBlockEntity be = FoldworksChestAccess.resolve(playerInventory.player.level().getBlockEntity(pos));
                return new FoldworksChestMenu(containerId, playerInventory, be);
            }));
}
