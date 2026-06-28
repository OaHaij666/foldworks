package com.pockethomestead.network;

import com.pockethomestead.client.ClientProductionStatsCache;
import com.pockethomestead.production.ProductionStatsStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record ProductionStatsSyncPacket(
        long serverGameTime,
        List<GroupData> groups,
        List<BucketData> buckets,
        List<InventoryData> inventories,
        List<MemberData> members,
        List<String> favoriteResources
) implements CustomPacketPayload {
    public static final Type<ProductionStatsSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "production_stats_sync"));

    public record GroupData(String id, String name, boolean aggregate, List<String> childIds, int order) {}
    public record BucketData(String groupId, String itemId, long bucketStart, int input, int output) {}
    public record InventoryData(String groupId, String itemId, int count) {}
    public record MemberData(String chestKey, String groupId) {}

    public static final StreamCodec<ByteBuf, ProductionStatsSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ProductionStatsSyncPacket decode(ByteBuf buf) {
            long gameTime = buf.readLong();
            List<GroupData> groups = new ArrayList<>();
            int groupCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < groupCount; i++) {
                groups.add(new GroupData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean(),
                        decodeStrings(buf),
                        ByteBufCodecs.VAR_INT.decode(buf)
                ));
            }
            List<BucketData> buckets = new ArrayList<>();
            int bucketCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < bucketCount; i++) {
                buckets.add(new BucketData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readLong(),
                        ByteBufCodecs.VAR_INT.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf)
                ));
            }
            List<InventoryData> inventories = new ArrayList<>();
            int inventoryCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < inventoryCount; i++) {
                inventories.add(new InventoryData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf)
                ));
            }
            List<MemberData> members = new ArrayList<>();
            int memberCount = ByteBufCodecs.VAR_INT.decode(buf);
            for (int i = 0; i < memberCount; i++) {
                members.add(new MemberData(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf)));
            }
            List<String> favoriteResources = decodeStrings(buf);
            return new ProductionStatsSyncPacket(gameTime, groups, buckets, inventories, members, favoriteResources);
        }

        @Override
        public void encode(ByteBuf buf, ProductionStatsSyncPacket pkt) {
            buf.writeLong(pkt.serverGameTime);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.groups.size());
            for (GroupData group : pkt.groups) {
                ByteBufCodecs.STRING_UTF8.encode(buf, group.id);
                ByteBufCodecs.STRING_UTF8.encode(buf, group.name);
                buf.writeBoolean(group.aggregate);
                encodeStrings(buf, group.childIds);
                ByteBufCodecs.VAR_INT.encode(buf, group.order);
            }
            ByteBufCodecs.VAR_INT.encode(buf, pkt.buckets.size());
            for (BucketData bucket : pkt.buckets) {
                ByteBufCodecs.STRING_UTF8.encode(buf, bucket.groupId);
                ByteBufCodecs.STRING_UTF8.encode(buf, bucket.itemId);
                buf.writeLong(bucket.bucketStart);
                ByteBufCodecs.VAR_INT.encode(buf, bucket.input);
                ByteBufCodecs.VAR_INT.encode(buf, bucket.output);
            }
            ByteBufCodecs.VAR_INT.encode(buf, pkt.inventories.size());
            for (InventoryData inventory : pkt.inventories) {
                ByteBufCodecs.STRING_UTF8.encode(buf, inventory.groupId);
                ByteBufCodecs.STRING_UTF8.encode(buf, inventory.itemId);
                ByteBufCodecs.VAR_INT.encode(buf, inventory.count);
            }
            ByteBufCodecs.VAR_INT.encode(buf, pkt.members.size());
            for (MemberData member : pkt.members) {
                ByteBufCodecs.STRING_UTF8.encode(buf, member.chestKey);
                ByteBufCodecs.STRING_UTF8.encode(buf, member.groupId);
            }
            encodeStrings(buf, pkt.favoriteResources);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static ProductionStatsSyncPacket from(ProductionStatsStorage.PlayerStats stats, long gameTime) {
        List<GroupData> groups = new ArrayList<>();
        for (ProductionStatsStorage.GroupSnapshot group : stats.groups()) {
            groups.add(new GroupData(group.id(), group.name(), group.aggregate(), group.childIds(), group.order()));
        }
        List<BucketData> buckets = new ArrayList<>();
        for (ProductionStatsStorage.BucketSnapshot bucket : stats.buckets(gameTime)) {
            buckets.add(new BucketData(bucket.groupId(), bucket.itemId(), bucket.bucketStart(), bucket.input(), bucket.output()));
        }
        List<InventoryData> inventories = new ArrayList<>();
        for (ProductionStatsStorage.InventorySnapshot inventory : stats.inventories()) {
            inventories.add(new InventoryData(inventory.groupId(), inventory.itemId(), inventory.count()));
        }
        List<MemberData> members = new ArrayList<>();
        for (ProductionStatsStorage.MemberSnapshot member : stats.members()) {
            members.add(new MemberData(member.chestKey(), member.groupId()));
        }
        List<String> favoriteResources = new ArrayList<>(stats.favoriteResources());
        return new ProductionStatsSyncPacket(gameTime, groups, buckets, inventories, members, favoriteResources);
    }

    public static void handle(ProductionStatsSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientProductionStatsCache.update(packet);
        });
    }

    private static List<String> decodeStrings(ByteBuf buf) {
        List<String> strings = new ArrayList<>();
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        for (int i = 0; i < count; i++) strings.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return strings;
    }

    private static void encodeStrings(ByteBuf buf, List<String> strings) {
        ByteBufCodecs.VAR_INT.encode(buf, strings.size());
        for (String value : strings) ByteBufCodecs.STRING_UTF8.encode(buf, value);
    }
}
