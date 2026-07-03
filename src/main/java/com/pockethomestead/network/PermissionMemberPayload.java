package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
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

/** C→S：新增/更新/删除空间成员。add=true 时按 name 解析；否则按 targetId 移除。 */
public record PermissionMemberPayload(UUID spaceId, boolean add, String name, UUID targetId,
                                      SpacePermission.MemberRole role,
                                      SpacePermission.AccessLevel overrideLevel) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PermissionMemberPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "permission_member"));
    private static final UUID EMPTY_UUID = new UUID(0, 0);

    public static final StreamCodec<ByteBuf, PermissionMemberPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, p.spaceId());
            ByteBufCodecs.BOOL.encode(buf, p.add());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.name());
            UUIDUtil.STREAM_CODEC.encode(buf, p.targetId());
            ByteBufCodecs.VAR_INT.encode(buf, p.role().ordinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.overrideLevel() == null ? -1 : p.overrideLevel().ordinal());
        },
        buf -> {
            UUID spaceId = UUIDUtil.STREAM_CODEC.decode(buf);
            boolean add = ByteBufCodecs.BOOL.decode(buf);
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            UUID targetId = UUIDUtil.STREAM_CODEC.decode(buf);
            int roleOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int overrideOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            SpacePermission.MemberRole[] roles = SpacePermission.MemberRole.values();
            SpacePermission.AccessLevel[] levels = SpacePermission.AccessLevel.values();
            SpacePermission.MemberRole role = roleOrdinal >= 0 && roleOrdinal < roles.length ? roles[roleOrdinal] : SpacePermission.MemberRole.MEMBER;
            SpacePermission.AccessLevel override = overrideOrdinal >= 0 && overrideOrdinal < levels.length ? levels[overrideOrdinal] : null;
            return new PermissionMemberPayload(spaceId, add, name, targetId, role, override);
        }
    );

    public static PermissionMemberPayload addByName(UUID spaceId, String name) {
        return addByName(spaceId, name, SpacePermission.MemberRole.MEMBER, null);
    }

    public static PermissionMemberPayload addByName(UUID spaceId, String name, SpacePermission.MemberRole role, SpacePermission.AccessLevel overrideLevel) {
        return new PermissionMemberPayload(spaceId, true, name, EMPTY_UUID, role, overrideLevel);
    }

    public static PermissionMemberPayload updateById(UUID spaceId, UUID targetId, SpacePermission.MemberRole role, SpacePermission.AccessLevel overrideLevel) {
        return new PermissionMemberPayload(spaceId, true, "", targetId, role, overrideLevel);
    }

    public static PermissionMemberPayload remove(UUID spaceId, UUID targetId) {
        return new PermissionMemberPayload(spaceId, false, "", targetId, SpacePermission.MemberRole.MEMBER, null);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleOnServer(PermissionMemberPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
            if (space == null || !space.can(player.getUUID(), SpacePermission.AccessLevel.MANAGE)) return;

            UUID targetId = payload.targetId();
            if (payload.add()) {
                if (!EMPTY_UUID.equals(payload.targetId())) {
                    targetId = payload.targetId();
                } else {
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
                    targetId = profileOpt.get().getId();
                }
                UUID memberId = targetId;
                SpaceManager.getInstance().updateOwnerPermission(space.getOwnerId(),
                        permission -> permission.setMember(memberId, payload.role(), payload.overrideLevel()));
            } else {
                UUID memberId = targetId;
                SpaceManager.getInstance().updateOwnerPermission(space.getOwnerId(),
                        permission -> permission.removeMember(memberId));
            }
            SpaceListPayload.sendToAll(player.server);
        });
    }
}
