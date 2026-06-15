package com.pockethomestead.client;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.client.screen.PickupChestScreen;
import com.pockethomestead.client.screen.SupplyChestScreen;
import com.pockethomestead.registration.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * 客户端事件订阅器 - 注册Screen
 */
@EventBusSubscriber(modid = PocketHomestead.MODID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.SUPPLY_CHEST.get(), SupplyChestScreen::new);
        event.register(ModMenuTypes.PICKUP_CHEST.get(), PickupChestScreen::new);

        PocketHomestead.LOGGER.info("口袋家园Screen已注册");
    }
}
