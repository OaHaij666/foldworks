package com.pockethomestead.registration;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.item.HomesteadTabletItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(PocketHomestead.MODID);

    // 尘歌玉盘：合并后的单一界面物品（创建 + 管理）
    public static final DeferredItem<HomesteadTabletItem> HOMESTEAD_TABLET = ITEMS.register("homestead_tablet",
            () -> new HomesteadTabletItem(new Item.Properties().stacksTo(1)));

    // 方块物品（由 DataGen 或手动注册）
    public static final DeferredItem<net.minecraft.world.item.BlockItem> SUPPLY_CHEST_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.SUPPLY_CHEST);
    public static final DeferredItem<net.minecraft.world.item.BlockItem> PICKUP_CHEST_ITEM = ITEMS.registerSimpleBlockItem(ModBlocks.PICKUP_CHEST);
}
