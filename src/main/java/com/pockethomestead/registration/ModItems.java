package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.item.HomesteadTabletItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PocketHomestead.MODID);

    public static final DeferredItem<HomesteadTabletItem> HOMESTEAD_TABLET = ITEMS.register("homestead_tablet",
            () -> new HomesteadTabletItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> STORAGE_UPGRADE = ITEMS.register("storage_upgrade",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> FLUID_UPGRADE = ITEMS.register("fluid_upgrade",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> NETWORK_UPGRADE = ITEMS.register("network_upgrade",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> ENERGY_TRANSFER_UPGRADE = ITEMS.register("energy_transfer_upgrade",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> STRESS_UPGRADE = ITEMS.register("stress_upgrade",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> SUITE_UPGRADE = ITEMS.register("suite_upgrade",
            () -> new Item(new Item.Properties()));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> HOMESTEAD_CHEST_ITEM =
            ITEMS.registerSimpleBlockItem(ModBlocks.HOMESTEAD_CHEST);
}
