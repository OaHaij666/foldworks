package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(
            Registries.CREATIVE_MODE_TAB, PocketHomestead.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> POCKET_HOMESTEAD_TAB = CREATIVE_MODE_TABS.register(
            "pocket_homestead_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.pockethomestead"))
                    .icon(() -> new ItemStack(ModItems.HOMESTEAD_TABLET.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.HOMESTEAD_TABLET.get());
                        output.accept(ModItems.HOMESTEAD_CHEST_ITEM.get());
                        output.accept(ModItems.STORAGE_UPGRADE.get());
                        output.accept(ModItems.FLUID_UPGRADE.get());
                        output.accept(ModItems.NETWORK_UPGRADE.get());
                        output.accept(ModItems.ENERGY_TRANSFER_UPGRADE.get());
                        output.accept(ModItems.STRESS_UPGRADE.get());
                    }).build());
}
