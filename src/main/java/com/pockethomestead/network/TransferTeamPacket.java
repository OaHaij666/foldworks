package com.pockethomestead.network;

import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphAccess;
import com.pockethomestead.transfer.TransferGraphStorage;
import com.pockethomestead.transfer.TransferTeam;
import com.pockethomestead.transfer.TransferTeamStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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
            if (teamId == null) return;
            TransferTeam team = storage.getTeam(teamId);
            if (team == null) return;

            if ("ACCEPT_INVITE".equals(packet.action)) {
                if (team.acceptInvite(player.getUUID())) {
                    storage.setDirty();
                    player.displayClientMessage(Component.translatable("pockethomestead.team.invite.accepted", team.name()), false);
                    RequestTransferGraphPacket.sendGraphTo(player, GraphKey.protectedGraph(teamId));
                    notifyTeamOwner(player, team, "pockethomestead.team.invite.accepted_notice", player.getName().getString(), team.name());
                }
                return;
            }
            if ("DECLINE_INVITE".equals(packet.action)) {
                if (team.declineInvite(player.getUUID())) {
                    storage.setDirty();
                    player.displayClientMessage(Component.translatable("pockethomestead.team.invite.declined", team.name()), false);
                    RequestTransferGraphPacket.sendGraphTo(player);
                    notifyTeamOwner(player, team, "pockethomestead.team.invite.declined_notice", player.getName().getString(), team.name());
                }
                return;
            }

            if (!team.canManageMembers(player.getUUID())) return;
            if ("DELETE".equals(packet.action)) {
                TransferGraph protectedGraph = TransferGraphStorage.get(player.server).getGraph(GraphKey.protectedGraph(teamId));
                if (protectedGraph != null && (!protectedGraph.getNodes().isEmpty() || !protectedGraph.getEdges().isEmpty())) {
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                            "pockethomestead.team.delete.not_empty"), false);
                    return;
                }
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
                // value 格式: "memberUuid|AccessLevel"
                String[] parts = packet.value == null ? new String[0] : packet.value.split("\\|", -1);
                if (parts.length != 2) return;
                UUID memberId = parseUuid(parts[0]);
                if (memberId == null && !parts[0].isBlank() && player.server.getProfileCache() != null) {
                    memberId = player.server.getProfileCache().get(parts[0].trim())
                            .map(com.mojang.authlib.GameProfile::getId)
                            .orElse(null);
                }
                SpacePermission.AccessLevel level;
                try {
                    level = SpacePermission.AccessLevel.valueOf(parts[1]);
                } catch (IllegalArgumentException ignored) {
                    return;
                }
                if (memberId != null) {
                    boolean alreadyMember = team.levelFor(memberId) != SpacePermission.AccessLevel.NONE;
                    if (level == SpacePermission.AccessLevel.NONE || alreadyMember) {
                        team.setMember(memberId, level);
                    } else {
                        team.inviteMember(memberId, level);
                        notifyInvitee(player, memberId, team);
                    }
                    storage.setDirty();
                } else if (!parts[0].isBlank()) {
                    player.sendSystemMessage(Component.translatable("pockethomestead.permission.player_not_found", parts[0])
                            .withStyle(ChatFormatting.RED));
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

    private static void notifyInvitee(ServerPlayer actor, UUID inviteeId, TransferTeam team) {
        ServerPlayer invitee = actor.server.getPlayerList().getPlayer(inviteeId);
        if (invitee == null) return;
        invitee.displayClientMessage(Component.translatable(
                "pockethomestead.team.invite.received", actor.getName().getString(), team.name()), false);
        RequestTransferGraphPacket.sendGraphTo(invitee);
    }

    private static void notifyTeamOwner(ServerPlayer actor, TransferTeam team, String key, Object... args) {
        if (team.owner() == null || team.owner().equals(actor.getUUID())) return;
        ServerPlayer owner = actor.server.getPlayerList().getPlayer(team.owner());
        if (owner != null) {
            owner.displayClientMessage(Component.translatable(key, args), false);
            RequestTransferGraphPacket.sendGraphTo(owner, GraphKey.protectedGraph(team.id()));
        }
    }
}
