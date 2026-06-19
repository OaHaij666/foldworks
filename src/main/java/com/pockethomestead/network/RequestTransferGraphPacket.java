package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.registry.ChestRegistryManager;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RequestTransferGraphPacket() implements CustomPacketPayload {
    public static final Type<RequestTransferGraphPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "request_transfer_graph"));

    public static final StreamCodec<ByteBuf, RequestTransferGraphPacket> STREAM_CODEC = StreamCodec.unit(new RequestTransferGraphPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestTransferGraphPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) sendGraphTo(player);
        });
    }

    public static void sendGraphTo(ServerPlayer player) {
        UUID owner = player.getUUID();
        TransferGraph graph = TransferGraphStorage.get(player.server).graphFor(owner);
        PacketDistributor.sendToPlayer(player, TransferGraphSyncPacket.from(
                graph,
                chestsFor(player, owner, ChestRegistryManager.ChestType.SUPPLY),
                chestsFor(player, owner, ChestRegistryManager.ChestType.PICKUP)
        ));
    }

    private static List<TransferGraphSyncPacket.ChestData> chestsFor(ServerPlayer player, UUID owner, ChestRegistryManager.ChestType type) {
        List<TransferGraphSyncPacket.ChestData> result = new ArrayList<>();
        Set<String> ids = ChestRegistryManager.getInstance().getAllChestIdsOfType(owner, type);
        for (String id : ids) {
            for (ChestRegistryManager.ChestLocation loc : ChestRegistryManager.getInstance().getChestLocationsByType(owner, id, type)) {
                result.add(new TransferGraphSyncPacket.ChestData(type.name(), id, loc.dimensionKey, loc.pos.asLong()));
            }
        }
        result.sort((a, b) -> a.chestId().compareToIgnoreCase(b.chestId()));
        return result;
    }
}
