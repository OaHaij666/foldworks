package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = PocketHomestead.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModMessages {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        registrar.playToServer(
                CreateSpacePayload.TYPE,
                CreateSpacePayload.STREAM_CODEC,
                CreateSpacePayload::handleOnServer
        );

        registrar.playToServer(
                CreateSpaceConfigPayload.TYPE,
                CreateSpaceConfigPayload.STREAM_CODEC,
                CreateSpaceConfigPayload::handleOnServer
        );

        registrar.playToServer(
                SpaceActionPayload.TYPE,
                SpaceActionPayload.STREAM_CODEC,
                SpaceActionPayload::handleOnServer
        );

        registrar.playToServer(
                RequestSpaceListPayload.TYPE,
                RequestSpaceListPayload.STREAM_CODEC,
                RequestSpaceListPayload::handleOnServer
        );

        registrar.playToServer(
                RequestDimensionBiomesPayload.TYPE,
                RequestDimensionBiomesPayload.STREAM_CODEC,
                RequestDimensionBiomesPayload::handleOnServer
        );

        registrar.playToServer(
                UpdatePermissionPayload.TYPE,
                UpdatePermissionPayload.STREAM_CODEC,
                UpdatePermissionPayload::handleOnServer
        );

        registrar.playToServer(
                PermissionMemberPayload.TYPE,
                PermissionMemberPayload.STREAM_CODEC,
                PermissionMemberPayload::handleOnServer
        );

        registrar.playToClient(
                SpaceListPayload.TYPE,
                SpaceListPayload.STREAM_CODEC,
                SpaceListPayload::handleOnClient
        );

        registrar.playToClient(
                DimensionBiomesPayload.TYPE,
                DimensionBiomesPayload.STREAM_CODEC,
                DimensionBiomesPayload::handleOnClient
        );
    }
}
