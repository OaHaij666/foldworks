package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.registry.ChestRegistryManager;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphStorage;
import com.pockethomestead.transfer.TransferGraphValidator;
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
        PacketDistributor.sendToPlayer(player, TransferGraphSyncPacket.from(graph, chestsFor(player, owner)));
        List<TransferGraphValidator.Issue> issues = new ArrayList<>(TransferGraphValidator.validate(graph));
        issues.addAll(TransferGraphValidator.validateRuntime(graph, player.server, owner));
        PacketDistributor.sendToPlayer(player, TransferGraphValidationPacket.from(issues));
    }

    private static List<TransferGraphSyncPacket.ChestData> chestsFor(ServerPlayer player, UUID owner) {
        List<TransferGraphSyncPacket.ChestData> result = new ArrayList<>();
        for (String id : ChestRegistryManager.getInstance().getPlayerChestIds(owner)) {
            for (ChestRegistryManager.ChestLocation loc : ChestRegistryManager.getInstance().getChestLocations(owner, id)) {
                if (!hasNetworkUpgrade(player, loc.dimensionKey, loc.pos)) continue;
                result.add(new TransferGraphSyncPacket.ChestData(id, loc.dimensionKey, loc.pos.asLong()));
            }
        }
        result.sort((a, b) -> {
            int c = a.chestId().compareToIgnoreCase(b.chestId());
            if (c != 0) return c;
            c = a.dimensionKey().compareToIgnoreCase(b.dimensionKey());
            if (c != 0) return c;
            return Long.compare(a.pos(), b.pos());
        });
        return result;
    }

    private static boolean hasNetworkUpgrade(ServerPlayer player, String dimensionKey, net.minecraft.core.BlockPos pos) {
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionKey);
        if (dimLoc == null) return false;
        ServerLevel level = player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
        ));
        BaseChestBlockEntity be = level == null ? null : HomesteadChestAccess.resolve(level.getBlockEntity(pos));
        return be != null && be.hasNetworkUpgrade();
    }
}
