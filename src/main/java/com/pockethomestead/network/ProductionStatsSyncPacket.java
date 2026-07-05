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
        String scopeKind,
        String scopeId,
        List<ScopeData> scopeOptions,
        List<GroupData> groups,
        List<BucketData> buckets,
        List<InventoryData> inventories,
        List<MemberData> members,
        List<String> favoriteResources
) implements CustomPacketPayload {
    public static final Type<ProductionStatsSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "production_stats_sync"));

    public record ScopeData(String kind, String id, String label, boolean writable) {}
    public record GroupData(String id, String name, boolean aggregate, List<String> childIds, int order) {}
    public record BucketData(String groupId, String itemId, long bucketStart, int input, int output) {}
    public record InventoryData(String groupId, String itemId, int count) {}
    public record MemberData(String chestKey, String groupId) {}

    private static final int MAX_GROUPS = 256;
    private static final int MAX_SCOPES = 256;
    private static final int MAX_BUCKETS = 4096;
    private static final int MAX_INVENTORIES = 4096;
    private static final int MAX_MEMBERS = 1024;
    private static final int MAX_STRINGS = 1024;

    public static final StreamCodec<ByteBuf, ProductionStatsSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ProductionStatsSyncPacket decode(ByteBuf buf) {
            long gameTime = buf.readLong();
            String scopeKind = ByteBufCodecs.STRING_UTF8.decode(buf);
            String scopeId = ByteBufCodecs.STRING_UTF8.decode(buf);
            List<ScopeData> scopeOptions = new ArrayList<>();
            int scopeCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_SCOPES);
            for (int i = 0; i < scopeCount; i++) {
                scopeOptions.add(new ScopeData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        buf.readBoolean()
                ));
            }
            List<GroupData> groups = new ArrayList<>();
            int groupCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_GROUPS);
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
            int bucketCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_BUCKETS);
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
            int inventoryCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_INVENTORIES);
            for (int i = 0; i < inventoryCount; i++) {
                inventories.add(new InventoryData(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf)
                ));
            }
            List<MemberData> members = new ArrayList<>();
            int memberCount = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_MEMBERS);
            for (int i = 0; i < memberCount; i++) {
                members.add(new MemberData(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf)));
            }
            List<String> favoriteResources = decodeStrings(buf);
            return new ProductionStatsSyncPacket(gameTime, scopeKind, scopeId, scopeOptions, groups, buckets, inventories, members, favoriteResources);
        }

        @Override
        public void encode(ByteBuf buf, ProductionStatsSyncPacket pkt) {
            buf.writeLong(pkt.serverGameTime);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.scopeKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.scopeId);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.scopeOptions.size());
            for (ScopeData scope : pkt.scopeOptions) {
                ByteBufCodecs.STRING_UTF8.encode(buf, scope.kind);
                ByteBufCodecs.STRING_UTF8.encode(buf, scope.id);
                ByteBufCodecs.STRING_UTF8.encode(buf, scope.label);
                buf.writeBoolean(scope.writable);
            }
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

    public static ProductionStatsSyncPacket from(String scopeKind, String scopeId, List<ScopeData> scopeOptions,
                                                 ProductionStatsStorage.PlayerStats stats, long gameTime) {
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
        return new ProductionStatsSyncPacket(gameTime, scopeKind, scopeId, List.copyOf(scopeOptions),
                groups, buckets, inventories, members, favoriteResources);
    }

    public static void handle(ProductionStatsSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientProductionStatsCache.update(packet);
        });
    }

    private static int checkCount(int count, int max) {
        if (count < 0 || count > max) throw new io.netty.handler.codec.DecoderException("List count out of range: " + count + " > " + max);
        return count;
    }

    private static List<String> decodeStrings(ByteBuf buf) {
        int count = checkCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_STRINGS);
        List<String> strings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) strings.add(ByteBufCodecs.STRING_UTF8.decode(buf));
        return strings;
    }

    private static void encodeStrings(ByteBuf buf, List<String> strings) {
        ByteBufCodecs.VAR_INT.encode(buf, strings.size());
        for (String value : strings) ByteBufCodecs.STRING_UTF8.encode(buf, value);
    }
}
