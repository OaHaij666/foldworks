package com.foldworks.registration;

import com.foldworks.Foldworks;
import com.foldworks.compat.create.CreateCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB, Foldworks.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FOLDWORKS_TAB = CREATIVE_MODE_TABS.register(
            "foldworks_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.foldworks"))
                    .icon(() -> new ItemStack(ModItems.FOLDWORKS_TABLET.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.FOLDWORKS_TABLET.get());
                        output.accept(ModItems.FOLDWORKS_CHEST_ITEM.get());
                        output.accept(ModItems.STORAGE_UPGRADE.get());
                        if (CreateCompat.isCreateLoaded()) output.accept(ModItems.FLUID_UPGRADE.get());
                        output.accept(ModItems.NETWORK_UPGRADE.get());
                        output.accept(ModItems.ENERGY_TRANSFER_UPGRADE.get());
                        if (CreateCompat.isCreateLoaded()) output.accept(ModItems.STRESS_UPGRADE.get());
                        output.accept(ModItems.SUITE_UPGRADE.get());
                    }).build());
}
