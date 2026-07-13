package com.foldworks.network;

import com.foldworks.Foldworks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RequestSpaceListPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestSpaceListPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "request_space_list"));

    public static final StreamCodec<ByteBuf, RequestSpaceListPayload> STREAM_CODEC =
            StreamCodec.unit(new RequestSpaceListPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleOnServer(RequestSpaceListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            SpaceListPayload.sendTo(player);
        });
    }
}
