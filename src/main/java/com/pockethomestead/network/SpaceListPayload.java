package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.client.ClientSpaceCache;
import com.pockethomestead.space.SpaceManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record SpaceListPayload(List<SpaceInfo> spaces) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SpaceListPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "space_list"));

    public static final StreamCodec<ByteBuf, SpaceListPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            var list = p.spaces();
            ByteBufCodecs.VAR_INT.encode(buf, list.size());
            for (SpaceInfo s : list) SpaceInfo.STREAM_CODEC.encode(buf, s);
        },
        buf -> {
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<SpaceInfo> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(SpaceInfo.STREAM_CODEC.decode(buf));
            return new SpaceListPayload(List.copyOf(list));
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** 服务端便捷方法：把该玩家可访问的最新空间列表推送给他。 */
    public static void sendTo(ServerPlayer player) {
        List<SpaceInfo> infos = SpaceManager.getInstance()
                .getAccessibleSpaces(player.getUUID())
                .stream().map(s -> SpaceInfo.from(player.server, s)).toList();
        PacketDistributor.sendToPlayer(player, new SpaceListPayload(infos));
    }

    public static void handleOnClient(SpaceListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientSpaceCache.update(payload.spaces()));
    }
}
