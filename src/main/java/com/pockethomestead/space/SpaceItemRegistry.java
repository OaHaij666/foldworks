package com.pockethomestead.space;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端静态注册表，跟踪四种方块实体的世界位置。
 * 由各 BlockEntity 在 onLoad() / setRemoved() 时自行注册/注销。
 */
public class SpaceItemRegistry {

    // 空间内的入口方块（PortalBlock）位置
    private static final Map<UUID, Set<BlockPos>> portalBlocks = new ConcurrentHashMap<>();
    // 空间内的出口方块（ExportBlock）位置
    private static final Map<UUID, Set<BlockPos>> exportBlocks = new ConcurrentHashMap<>();
    // 主世界的供货箱（SupplyChest）全局位置
    private static final Map<UUID, Set<GlobalPos>> supplyChests = new ConcurrentHashMap<>();
    // 主世界的取货箱（PickupChest）全局位置
    private static final Map<UUID, Set<GlobalPos>> pickupChests = new ConcurrentHashMap<>();

    private SpaceItemRegistry() {}

    // ── Portal ────────────────────────────────────────────────────────────────

    public static void registerPortal(UUID spaceId, BlockPos pos) {
        portalBlocks.computeIfAbsent(spaceId, k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregisterPortal(UUID spaceId, BlockPos pos) {
        Set<BlockPos> set = portalBlocks.get(spaceId);
        if (set != null) set.remove(pos);
    }

    public static Set<BlockPos> getPortalBlocks(UUID spaceId) {
        return portalBlocks.getOrDefault(spaceId, Set.of());
    }

    // ── Export ────────────────────────────────────────────────────────────────

    public static void registerExport(UUID spaceId, BlockPos pos) {
        exportBlocks.computeIfAbsent(spaceId, k -> ConcurrentHashMap.newKeySet()).add(pos.immutable());
    }

    public static void unregisterExport(UUID spaceId, BlockPos pos) {
        Set<BlockPos> set = exportBlocks.get(spaceId);
        if (set != null) set.remove(pos);
    }

    public static Set<BlockPos> getExportBlocks(UUID spaceId) {
        return exportBlocks.getOrDefault(spaceId, Set.of());
    }

    // ── Supply Chest ──────────────────────────────────────────────────────────

    public static void registerSupply(UUID spaceId, ResourceKey<Level> level, BlockPos pos) {
        supplyChests.computeIfAbsent(spaceId, k -> ConcurrentHashMap.newKeySet())
                .add(GlobalPos.of(level, pos.immutable()));
    }

    public static void unregisterSupply(UUID spaceId, ResourceKey<Level> level, BlockPos pos) {
        Set<GlobalPos> set = supplyChests.get(spaceId);
        if (set != null) set.remove(GlobalPos.of(level, pos));
    }

    public static Set<GlobalPos> getSupplyChests(UUID spaceId) {
        return supplyChests.getOrDefault(spaceId, Set.of());
    }

    // ── Pickup Chest ──────────────────────────────────────────────────────────

    public static void registerPickup(UUID spaceId, ResourceKey<Level> level, BlockPos pos) {
        pickupChests.computeIfAbsent(spaceId, k -> ConcurrentHashMap.newKeySet())
                .add(GlobalPos.of(level, pos.immutable()));
    }

    public static void unregisterPickup(UUID spaceId, ResourceKey<Level> level, BlockPos pos) {
        Set<GlobalPos> set = pickupChests.get(spaceId);
        if (set != null) set.remove(GlobalPos.of(level, pos));
    }

    public static Set<GlobalPos> getPickupChests(UUID spaceId) {
        return pickupChests.getOrDefault(spaceId, Set.of());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static void removeSpace(UUID spaceId) {
        portalBlocks.remove(spaceId);
        exportBlocks.remove(spaceId);
        supplyChests.remove(spaceId);
        pickupChests.remove(spaceId);
    }

    public static void clearAll() {
        portalBlocks.clear();
        exportBlocks.clear();
        supplyChests.clear();
        pickupChests.clear();
    }
}
