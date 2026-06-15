package com.pockethomestead.network;

import com.pockethomestead.menu.BaseChestMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 服务端→客户端：同步箱子完整配置（含可用绑定列表）
 */
public record ChestSyncPacket(
    String chestId,
    String boundTargetId,
    boolean transferEnabled,
    boolean voidModeEnabled,
    int transferRateLimit,
    List<String> availableBindings
) implements CustomPacketPayload {
    public static final Type<ChestSyncPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("pockethomestead", "chest_sync"));

    public static final StreamCodec<ByteBuf, ChestSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChestSyncPacket decode(ByteBuf buf) {
            String chestId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String boundTargetId = ByteBufCodecs.STRING_UTF8.decode(buf);
            boolean transferEnabled = buf.readBoolean();
            boolean voidModeEnabled = buf.readBoolean();
            int transferRateLimit = ByteBufCodecs.VAR_INT.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> bindings = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                bindings.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            }
            return new ChestSyncPacket(chestId, boundTargetId, transferEnabled, voidModeEnabled,
                transferRateLimit, Collections.unmodifiableList(bindings));
        }

        @Override
        public void encode(ByteBuf buf, ChestSyncPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.chestId);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.boundTargetId);
            buf.writeBoolean(pkt.transferEnabled);
            buf.writeBoolean(pkt.voidModeEnabled);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.transferRateLimit);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.availableBindings.size());
            for (String binding : pkt.availableBindings) {
                ByteBufCodecs.STRING_UTF8.encode(buf, binding);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * 客户端处理：将同步数据写入当前打开的箱子Screen缓存
     */
    public static void handle(ChestSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof com.pockethomestead.client.screen.BaseChestScreen<?> screen) {
                screen.cacheConfig(packet);
            }
        });
    }
}
