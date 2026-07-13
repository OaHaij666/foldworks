package com.foldworks.network;

import com.foldworks.Foldworks;
import com.foldworks.dimension.ProductionSpaceManager;
import com.foldworks.permission.AccessControl;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import io.netty.handler.codec.DecoderException;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public class SpaceActionPayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SpaceActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "space_action"));

    public enum Action {
        ENTER, EXIT, DELETE
    }

    private static final Action[] ACTION_VALUES = Action.values();

    private final Action action;
    private final UUID spaceId;

    public static final StreamCodec<ByteBuf, SpaceActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(
                    i -> {
                        if (i < 0 || i >= ACTION_VALUES.length)
                            throw new DecoderException("Invalid action ordinal: " + i);
                        return ACTION_VALUES[i];
                    },
                    Action::ordinal
            ),
            SpaceActionPayload::action,
            UUIDUtil.STREAM_CODEC,
            SpaceActionPayload::spaceId,
            SpaceActionPayload::new
    );

    public SpaceActionPayload(Action action, UUID spaceId) {
        this.action = action;
        this.spaceId = spaceId;
    }

    public Action action() { return action; }
    public UUID spaceId() { return spaceId; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(SpaceActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer player)) return;
                switch (payload.action()) {
                    case ENTER -> {
                        SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
                        if (space == null || !AccessControl.canEnterSpace(player, space)) {
                            AccessControl.deny(player);
                            return;
                        }
                        ProductionSpaceManager.getInstance().teleportToSpace(player, space);
                    }
                    case EXIT -> ProductionSpaceManager.getInstance().exitToReturnPosition(player);
                    case DELETE -> {
                        SpaceData space = SpaceManager.getInstance().getSpace(payload.spaceId());
                        if (space == null || !space.isOwner(player.getUUID())) {
                            AccessControl.deny(player);
                            return;
                        }
                        SpaceManager.getInstance().deleteSpace(player.server, payload.spaceId(), player.getUUID());
                        SpaceListPayload.sendToAll(player.server);
                    }
                }
            } catch (Exception e) {
                Foldworks.LOGGER.error("空间操作失败: action={}", payload.action(), e);
            }
        });
    }
}
