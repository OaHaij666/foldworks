package com.pockethomestead.client;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.client.model.HomesteadChestBakedModel;
import com.pockethomestead.client.renderer.HomesteadChestRenderer;
import com.pockethomestead.client.screen.HomesteadChestScreen;
import com.pockethomestead.registration.ModBlockEntities;
import com.pockethomestead.registration.ModMenuTypes;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class ClientEvents {
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ClientEvents::onRegisterScreens);
        modEventBus.addListener(ClientEvents::onRegisterRenderers);
        modEventBus.addListener(ClientEvents::onModifyBakingResult);
    }

    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.HOMESTEAD_CHEST.get(), HomesteadChestScreen::new);
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.HOMESTEAD_CHEST.get(), HomesteadChestRenderer::new);
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ResourceLocation chestModel = ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "homestead_chest");
        event.getModels().replaceAll((location, model) -> {
            if (!location.id().equals(chestModel)) return model;
            if (ModelResourceLocation.INVENTORY_VARIANT.equals(location.getVariant())) return model;
            return new HomesteadChestBakedModel(model, event.getTextureGetter());
        });
    }
}
