package com.foldworks.registration;

import com.foldworks.Foldworks;
import com.foldworks.config.ModConfig;
import com.foldworks.item.FoldworksTabletItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Foldworks.MODID);

    public static final DeferredItem<FoldworksTabletItem> FOLDWORKS_TABLET = ITEMS.register("foldworks_tablet",
            () -> new FoldworksTabletItem(new Item.Properties()
                    .stacksTo(1)
                    .attributes(FoldworksTabletItem.createTabletAttributes(8.0, -2.4))));

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

    public static final DeferredItem<net.minecraft.world.item.BlockItem> FOLDWORKS_CHEST_ITEM =
            ITEMS.registerSimpleBlockItem(ModBlocks.FOLDWORKS_CHEST);
}
