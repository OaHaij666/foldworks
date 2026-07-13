package com.foldworks.client;

import com.foldworks.Foldworks;
import com.foldworks.client.model.FoldworksChestBakedModel;
import com.foldworks.client.renderer.FoldworksChestRenderer;
import com.foldworks.client.screen.FoldworksChestScreen;
import com.foldworks.registration.ModBlockEntities;
import com.foldworks.registration.ModMenuTypes;
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
        event.register(ModMenuTypes.FOLDWORKS_CHEST.get(), FoldworksChestScreen::new);
    }

    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.FOLDWORKS_CHEST.get(), FoldworksChestRenderer::new);
    }

    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ResourceLocation chestModel = ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "foldworks_chest");
        event.getModels().replaceAll((location, model) -> {
            if (!location.id().equals(chestModel)) return model;
            if (ModelResourceLocation.INVENTORY_VARIANT.equals(location.getVariant())) return model;
            return new FoldworksChestBakedModel(model, event.getTextureGetter());
        });
    }
}
