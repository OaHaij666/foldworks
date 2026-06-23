package com.pockethomestead.client;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.client.screen.HomesteadChestScreen;
import com.pockethomestead.registration.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = PocketHomestead.MODID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.HOMESTEAD_CHEST.get(), HomesteadChestScreen::new);
        PocketHomestead.LOGGER.info("口袋家园Screen已注册");
    }
}
