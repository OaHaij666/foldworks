package com.pockethomestead.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端→客户端：同步箱子完整配置 + 物品快照（itemId → count）
 * 物品快照用于客户端纯渲染箱子区（1物品=1格，真实数量）。
 * maxCapacity / nextTransferSeconds / syncIntervalSeconds 由服务端权威下发，
 * 避免客户端 config 状态不一致。
 */
public record ChestSyncPacket(
    String chestId,
    String boundTargetId,
    boolean transferEnabled,
    boolean voidModeEnabled,
    int transferRateLimit,
    int syncIntervalSeconds,
    int nextTransferSeconds,
    int maxCapacity,
    List<String> availableBindings,
    Map<String, Integer> items
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
            int syncIntervalSeconds = ByteBufCodecs.VAR_INT.decode(buf);
            int nextTransferSeconds = ByteBufCodecs.VAR_INT.decode(buf);
            int maxCapacity = ByteBufCodecs.VAR_INT.decode(buf);

            int bindCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> bindings = new ArrayList<>();
            for (int i = 0; i < bindCount; i++) {
                bindings.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            }

            int itemCount = ByteBufCodecs.VAR_INT.decode(buf);
            Map<String, Integer> items = new LinkedHashMap<>();
            for (int i = 0; i < itemCount; i++) {
                String id = ByteBufCodecs.STRING_UTF8.decode(buf);
                int count = ByteBufCodecs.VAR_INT.decode(buf);
                items.put(id, count);
            }

            return new ChestSyncPacket(chestId, boundTargetId, transferEnabled, voidModeEnabled,
                transferRateLimit, syncIntervalSeconds, nextTransferSeconds, maxCapacity,
                Collections.unmodifiableList(bindings), Collections.unmodifiableMap(items));
        }

        @Override
        public void encode(ByteBuf buf, ChestSyncPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.chestId);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.boundTargetId);
            buf.writeBoolean(pkt.transferEnabled);
            buf.writeBoolean(pkt.voidModeEnabled);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.transferRateLimit);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.syncIntervalSeconds);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.nextTransferSeconds);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.maxCapacity);

            ByteBufCodecs.VAR_INT.encode(buf, pkt.availableBindings.size());
            for (String binding : pkt.availableBindings) {
                ByteBufCodecs.STRING_UTF8.encode(buf, binding);
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.items.size());
            for (Map.Entry<String, Integer> e : pkt.items.entrySet()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.getKey());
                ByteBufCodecs.VAR_INT.encode(buf, e.getValue());
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
