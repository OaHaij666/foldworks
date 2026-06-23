package com.pockethomestead.space;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端静态注册表，跟踪空间相关方块实体的世界位置。
 * 由各 BlockEntity 在 onLoad() / setRemoved() 时自行注册/注销。
 */
public class SpaceItemRegistry {

    // 空间内的入口方块（PortalBlock）位置
    private static final Map<UUID, Set<BlockPos>> portalBlocks = new ConcurrentHashMap<>();
    // 空间内的出口方块（ExportBlock）位置
    private static final Map<UUID, Set<BlockPos>> exportBlocks = new ConcurrentHashMap<>();
    // 主世界的传输箱全局位置
    private static final Map<UUID, Set<GlobalPos>> chests = new ConcurrentHashMap<>();

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

    // ── Homestead Chest ───────────────────────────────────────────────────────

    public static void registerChest(UUID spaceId, ResourceKey<Level> level, BlockPos pos) {
        chests.computeIfAbsent(spaceId, k -> ConcurrentHashMap.newKeySet())
                .add(GlobalPos.of(level, pos.immutable()));
    }

    public static void unregisterChest(UUID spaceId, ResourceKey<Level> level, BlockPos pos) {
        Set<GlobalPos> set = chests.get(spaceId);
        if (set != null) set.remove(GlobalPos.of(level, pos));
    }

    public static Set<GlobalPos> getChests(UUID spaceId) {
        return chests.getOrDefault(spaceId, Set.of());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static void removeSpace(UUID spaceId) {
        portalBlocks.remove(spaceId);
        exportBlocks.remove(spaceId);
        chests.remove(spaceId);
    }

    public static void clearAll() {
        portalBlocks.clear();
        exportBlocks.clear();
        chests.clear();
    }
}
