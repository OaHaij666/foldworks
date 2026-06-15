package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpaceStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/** C→S：向白/黑名单增删玩家。add=true 时按 name 解析；否则按 targetId 移除。 */
public record PermissionMemberPayload(UUID spaceId, boolean add, String name, UUID targetId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PermissionMemberPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "permission_member"));

    public static final StreamCodec<ByteBuf, PermissionMemberPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, p.spaceId());
            ByteBufCodecs.BOOL.encode(buf, p.add());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.name());
            UUIDUtil.STREAM_CODEC.encode(buf, p.targetId());
        },
        buf -> new PermissionMemberPayload(
            UUIDUtil.STREAM_CODEC.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf)
        )
    );

    public static PermissionMemberPayload addByName(UUID spaceId, String name) {
        return new PermissionMemberPayload(spaceId, true, name, new UUID(0, 0));
    }

    public static PermissionMemberPayload remove(UUID spaceId, UUID targetId) {
        return new PermissionMemberPayload(spaceId, false, "", targetId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleOnServer(PermissionMemberPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
            if (space == null || !space.isOwner(player.getUUID())) return;

            if (payload.add()) {
                String name = payload.name().trim();
                if (name.isEmpty()) return;
                var profileOpt = player.server.getProfileCache() != null
                        ? player.server.getProfileCache().get(name)
                        : java.util.Optional.<com.mojang.authlib.GameProfile>empty();
                if (profileOpt.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("pockethomestead.permission.player_not_found", name)
                            .withStyle(ChatFormatting.RED));
                    return;
                }
                space.getPermission().addMember(profileOpt.get().getId());
            } else {
                space.getPermission().removeMember(payload.targetId());
            }
            SpaceStorage.markDirty();
            SpaceListPayload.sendTo(player);
        });
    }
}
