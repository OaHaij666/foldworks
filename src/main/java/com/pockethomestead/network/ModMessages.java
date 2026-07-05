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
                RenameSpacePayload.TYPE,
                RenameSpacePayload.STREAM_CODEC,
                RenameSpacePayload::handleOnServer
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
                UpdateOfflineSimulationPayload.TYPE,
                UpdateOfflineSimulationPayload.STREAM_CODEC,
                UpdateOfflineSimulationPayload::handleOnServer
        );

        registrar.playToServer(
                UpdateSpaceChunkLoadingPayload.TYPE,
                UpdateSpaceChunkLoadingPayload.STREAM_CODEC,
                UpdateSpaceChunkLoadingPayload::handleOnServer
        );

        registrar.playToServer(
                PermissionMemberPayload.TYPE,
                PermissionMemberPayload.STREAM_CODEC,
                PermissionMemberPayload::handleOnServer
        );

        registrar.playToServer(
                SpaceArchiveRequestPacket.TYPE,
                SpaceArchiveRequestPacket.STREAM_CODEC,
                SpaceArchiveRequestPacket::handleOnServer
        );

        registrar.playToServer(
                SpaceArchiveClientChunkPacket.TYPE,
                SpaceArchiveClientChunkPacket.STREAM_CODEC,
                SpaceArchiveClientChunkPacket::handleOnServer
        );

        registrar.playToClient(
                SpaceListPayload.TYPE,
                SpaceListPayload.STREAM_CODEC,
                SpaceListPayload::handleOnClient
        );

        registrar.playToClient(
                SpaceArchiveServerPacket.TYPE,
                SpaceArchiveServerPacket.STREAM_CODEC,
                SpaceArchiveServerPacket::handleOnClient
        );

        registrar.playToClient(
                DimensionBiomesPayload.TYPE,
                DimensionBiomesPayload.STREAM_CODEC,
                DimensionBiomesPayload::handleOnClient
        );

        // 以下原在 PocketHomestead.registerPayloads 中注册，统一到此

        registrar.playToServer(
                ChestConfigPacket.TYPE,
                ChestConfigPacket.STREAM_CODEC,
                ChestConfigPacket::handle
        );
        registrar.playToClient(
                ChestSyncPacket.TYPE,
                ChestSyncPacket.STREAM_CODEC,
                ChestSyncPacket::handle
        );
        registrar.playToServer(
                TabletChestActionPacket.TYPE,
                TabletChestActionPacket.STREAM_CODEC,
                TabletChestActionPacket::handleOnServer
        );
        registrar.playToClient(
                TabletChestSyncPacket.TYPE,
                TabletChestSyncPacket.STREAM_CODEC,
                TabletChestSyncPacket::handleOnClient
        );
        registrar.playToServer(
                RequestTransferGraphPacket.TYPE,
                RequestTransferGraphPacket.STREAM_CODEC,
                RequestTransferGraphPacket::handle
        );
        registrar.playToServer(
                SaveTransferGraphPacket.TYPE,
                SaveTransferGraphPacket.STREAM_CODEC,
                SaveTransferGraphPacket::handle
        );
        registrar.playToClient(
                TransferGraphSyncPacket.TYPE,
                TransferGraphSyncPacket.STREAM_CODEC,
                TransferGraphSyncPacket::handle
        );
        registrar.playToClient(
                TransferGraphValidationPacket.TYPE,
                TransferGraphValidationPacket.STREAM_CODEC,
                TransferGraphValidationPacket::handle
        );
        registrar.playToServer(
                TransferTeamPacket.TYPE,
                TransferTeamPacket.STREAM_CODEC,
                TransferTeamPacket::handle
        );
        registrar.playToServer(
                RequestProductionStatsPacket.TYPE,
                RequestProductionStatsPacket.STREAM_CODEC,
                RequestProductionStatsPacket::handle
        );
        registrar.playToServer(
                UpdateProductionStatsPacket.TYPE,
                UpdateProductionStatsPacket.STREAM_CODEC,
                UpdateProductionStatsPacket::handle
        );
        registrar.playToClient(
                ProductionStatsSyncPacket.TYPE,
                ProductionStatsSyncPacket.STREAM_CODEC,
                ProductionStatsSyncPacket::handle
        );
    }
}
