package com.pockethomestead.client;

import com.pockethomestead.network.ProductionStatsSyncPacket;
import com.pockethomestead.production.ProductionStatsStorage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientProductionStatsCache {
    private ClientProductionStatsCache() {}

    public record ProductionRow(String itemId, int currentCount, int inputRatePerMinute, int outputRatePerMinute,
                                int netRatePerMinute, List<Integer> trendInput, List<Integer> trendOutput,
                                List<Integer> trendNet) {}

    private static long serverGameTime;
    private static List<ProductionStatsSyncPacket.GroupData> groups = List.of();
    private static List<ProductionStatsSyncPacket.BucketData> buckets = List.of();
    private static List<ProductionStatsSyncPacket.InventoryData> inventories = List.of();
    private static List<ProductionStatsSyncPacket.MemberData> members = List.of();
    private static Set<String> favoriteResources = Set.of();

    public static void update(ProductionStatsSyncPacket packet) {
        serverGameTime = packet.serverGameTime();
        groups = List.copyOf(packet.groups());
        buckets = List.copyOf(packet.buckets());
        inventories = List.copyOf(packet.inventories());
        members = List.copyOf(packet.members());
        favoriteResources = new LinkedHashSet<>(packet.favoriteResources());
    }

    public static long serverGameTime() { return serverGameTime; }
    public static List<ProductionStatsSyncPacket.GroupData> groups() { return groups; }
    public static List<ProductionStatsSyncPacket.BucketData> buckets() { return buckets; }
    public static List<ProductionStatsSyncPacket.InventoryData> inventories() { return inventories; }
    public static List<ProductionStatsSyncPacket.MemberData> members() { return members; }
    public static Set<String> favoriteResources() { return Set.copyOf(favoriteResources); }

    public static boolean isFavoriteResource(String itemId) {
        return itemId != null && favoriteResources.contains(itemId);
    }

    public static void setFavoriteResourceLocal(String itemId, boolean favorite) {
        if (itemId == null || itemId.isBlank()) return;
        LinkedHashSet<String> next = new LinkedHashSet<>(favoriteResources);
        if (favorite) next.add(itemId);
        else next.remove(itemId);
        favoriteResources = next;
    }

    public static ProductionStatsSyncPacket.GroupData group(String groupId) {
        for (ProductionStatsSyncPacket.GroupData group : groups) if (group.id().equals(groupId)) return group;
        return null;
    }

    public static String defaultGroupId() {
        return ProductionStatsStorage.DEFAULT_GROUP_ID;
    }

    public static List<ProductionStatsSyncPacket.GroupData> atomicGroups() {
        List<ProductionStatsSyncPacket.GroupData> rows = new ArrayList<>();
        for (ProductionStatsSyncPacket.GroupData group : groups) if (!group.aggregate()) rows.add(group);
        return rows;
    }

    public static String firstGroupId() {
        if (group(defaultGroupId()) != null) return defaultGroupId();
        return groups.isEmpty() ? defaultGroupId() : groups.get(0).id();
    }

    public static List<ProductionRow> rowsFor(String groupId, int minutes) {
        int safeMinutes = Math.max(1, minutes);
        long rangeTicks = safeMinutes * 60L * 20L;
        long cutoff = serverGameTime - rangeTicks;
        Set<String> atomics = atomicDescendants(groupId);
        Map<String, Accumulator> byItem = new LinkedHashMap<>();

        for (ProductionStatsSyncPacket.InventoryData inventory : inventories) {
            if (!atomics.contains(inventory.groupId())) continue;
            Accumulator acc = byItem.computeIfAbsent(inventory.itemId(), id -> new Accumulator());
            acc.current += inventory.count();
        }

        for (ProductionStatsSyncPacket.BucketData bucket : buckets) {
            if (!atomics.contains(bucket.groupId()) || bucket.bucketStart() < cutoff) continue;
            Accumulator acc = byItem.computeIfAbsent(bucket.itemId(), id -> new Accumulator());
            acc.input += bucket.input();
            acc.output += bucket.output();
            int index = trendIndex(bucket.bucketStart(), cutoff, rangeTicks);
            if (index >= 0 && index < acc.trendNet.length) {
                acc.trendInput[index] += bucket.input();
                acc.trendOutput[index] += bucket.output();
                acc.trendNet[index] += bucket.input() - bucket.output();
            }
        }

        List<ProductionRow> rows = new ArrayList<>();
        for (Map.Entry<String, Accumulator> entry : byItem.entrySet()) {
            Accumulator acc = entry.getValue();
            int inputRate = Math.round(acc.input / (float) safeMinutes);
            int outputRate = Math.round(acc.output / (float) safeMinutes);
            List<Integer> trendInput = new ArrayList<>(acc.trendInput.length);
            List<Integer> trendOutput = new ArrayList<>(acc.trendOutput.length);
            List<Integer> trendNet = new ArrayList<>(acc.trendNet.length);
            for (int i = 0; i < acc.trendNet.length; i++) {
                trendInput.add(Math.round(acc.trendInput[i] / (float) safeMinutes));
                trendOutput.add(Math.round(acc.trendOutput[i] / (float) safeMinutes));
                trendNet.add(Math.round(acc.trendNet[i] / (float) safeMinutes));
            }
            rows.add(new ProductionRow(entry.getKey(), acc.current, inputRate, outputRate, inputRate - outputRate,
                    trendInput, trendOutput, trendNet));
        }
        return rows;
    }

    public static Set<String> atomicDescendants(String groupId) {
        Set<String> result = new LinkedHashSet<>();
        collectAtomics(groupId, result, new LinkedHashSet<>());
        if (result.isEmpty() && group(groupId) != null && !group(groupId).aggregate()) result.add(groupId);
        return result;
    }

    public static boolean isChild(String parentId, String childId) {
        ProductionStatsSyncPacket.GroupData group = group(parentId);
        return group != null && group.childIds().contains(childId);
    }

    private static void collectAtomics(String groupId, Set<String> result, Set<String> visiting) {
        if (groupId == null || !visiting.add(groupId)) return;
        ProductionStatsSyncPacket.GroupData group = group(groupId);
        if (group == null) return;
        if (!group.aggregate()) {
            result.add(group.id());
        } else {
            for (String child : group.childIds()) collectAtomics(child, result, visiting);
        }
        visiting.remove(groupId);
    }

    private static int trendIndex(long bucketStart, long cutoff, long rangeTicks) {
        if (rangeTicks <= 0 || bucketStart < cutoff) return -1;
        long offset = bucketStart - cutoff;
        int index = (int) (offset * Accumulator.TREND_POINTS / rangeTicks);
        return Math.max(0, Math.min(Accumulator.TREND_POINTS - 1, index));
    }

    private static final class Accumulator {
        private static final int TREND_POINTS = 24;
        private int current;
        private int input;
        private int output;
        private final int[] trendInput = new int[TREND_POINTS];
        private final int[] trendOutput = new int[TREND_POINTS];
        private final int[] trendNet = new int[TREND_POINTS];
    }
}
