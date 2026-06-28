package com.pockethomestead.network;

import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferGraphAccess;
import com.pockethomestead.transfer.TransferGraphStorage;
import com.pockethomestead.transfer.TransferTeam;
import com.pockethomestead.transfer.TransferTeamStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record TransferTeamPacket(String action, String teamId, String value) implements CustomPacketPayload {
    public static final Type<TransferTeamPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "transfer_team"));

    public static final StreamCodec<ByteBuf, TransferTeamPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TransferTeamPacket decode(ByteBuf buf) {
            return new TransferTeamPacket(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, TransferTeamPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.action);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.teamId);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.value);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TransferTeamPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            TransferTeamStorage storage = TransferTeamStorage.get(player.server);
            if ("CREATE".equals(packet.action)) {
                TransferTeam team = storage.createTeam(player.getUUID(), packet.value == null || packet.value.isBlank() ? player.getName().getString() + "的团队" : packet.value);
                RequestTransferGraphPacket.sendGraphTo(player, GraphKey.protectedGraph(team.id()));
                return;
            }
            UUID teamId = parseUuid(packet.teamId);
            if (teamId == null || !TransferGraphAccess.canManage(player, GraphKey.protectedGraph(teamId))) return;
            TransferTeam team = storage.getTeam(teamId);
            if (team == null) return;
            if ("DELETE".equals(packet.action)) {
                if (storage.deleteTeam(teamId, player.getUUID())) {
                    TransferGraphStorage.get(player.server).removeGraph(GraphKey.protectedGraph(teamId));
                    RequestTransferGraphPacket.sendGraphTo(player, GraphKey.privateGraph(player.getUUID()));
                }
                return;
            }
            if ("RENAME".equals(packet.action)) {
                team.setName(packet.value);
                storage.setDirty();
            } else if ("SET_MEMBER".equals(packet.action)) {
                String[] parts = packet.value == null ? new String[0] : packet.value.split("\\|", -1);
                UUID memberId = parts.length > 0 ? parseUuid(parts[0]) : null;
                SpacePermission.AccessLevel level = SpacePermission.AccessLevel.NONE;
                try {
                    if (parts.length > 1) level = SpacePermission.AccessLevel.valueOf(parts[1]);
                } catch (IllegalArgumentException ignored) {
                }
                if (memberId != null) {
                    team.setMember(memberId, level);
                    storage.setDirty();
                }
            }
            RequestTransferGraphPacket.sendGraphTo(player, GraphKey.protectedGraph(teamId));
        });
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
