package com.pockethomestead.registry;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.moving.MovingChestRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChestRegistryManager {
    private static final ChestRegistryManager INSTANCE = new ChestRegistryManager();

    public static ChestRegistryManager getInstance() {
        return INSTANCE;
    }

    private final Map<UUID, Map<String, List<ChestLocation>>> playerChests = new ConcurrentHashMap<>();

    public static class ChestLocation {
        public final String dimensionKey;
        public final BlockPos pos;

        public ChestLocation(String dimensionKey, BlockPos pos) {
            this.dimensionKey = dimensionKey;
            this.pos = pos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChestLocation that)) return false;
            return Objects.equals(dimensionKey, that.dimensionKey) && Objects.equals(pos, that.pos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimensionKey, pos);
        }
    }

    public record RegisteredChest(UUID ownerUUID, String chestId, ChestLocation location) {}

    public void registerChest(UUID ownerUUID, String chestId, Level level, BlockPos pos) {
        if (ownerUUID == null || chestId == null || chestId.isEmpty() || level == null || pos == null) return;
        String dimKey = level.dimension().location().toString();
        ChestLocation location = new ChestLocation(dimKey, pos);
        List<ChestLocation> locations = playerChests.computeIfAbsent(ownerUUID, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chestId, k -> Collections.synchronizedList(new ArrayList<>()));
        // 复合操作 contains+add 必须在同步块内原子完成，否则并发线程可能重复添加
        synchronized (locations) {
            if (!locations.contains(location)) locations.add(location);
        }
    }

    public void unregisterChest(UUID ownerUUID, String chestId, Level level, BlockPos pos) {
        if (ownerUUID == null || chestId == null || level == null || pos == null) return;
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) return;
        List<ChestLocation> locations = chests.get(chestId);
        if (locations == null) return;
        String dimKey = level.dimension().location().toString();
        // removeIf+isEmpty+remove 序列非原子：另一线程可能在 removeIf 后、isEmpty 前添加新位置，
        // 导致 isEmpty 返回 false 而不清理空列表。用 synchronized 保护整个序列。
        synchronized (locations) {
            locations.removeIf(loc -> loc.dimensionKey.equals(dimKey) && loc.pos.equals(pos));
            if (locations.isEmpty()) chests.remove(chestId);
        }
        if (chests.isEmpty()) playerChests.remove(ownerUUID);
    }

    public void updateChestId(UUID ownerUUID, String oldId, String newId, Level level, BlockPos pos) {
        if (ownerUUID == null || oldId == null || newId == null) return;
        unregisterChest(ownerUUID, oldId, level, pos);
        registerChest(ownerUUID, newId, level, pos);
    }

    /**
     * 迁移箱子注册坐标：把 (oldDimKey, oldPos) 从 owner+chestId 的位置列表中移除，
     * 添加 (newDimKey, newPos)。用于兼容 Mekanism Cardboard Box 等"方块位置移动"场景。
     * 与 register/unregister 不同，这里直接用 dimKey 字符串，因为旧维度可能此时已经无法拿到 Level 对象。
     */
    public void relocateChest(UUID ownerUUID, String chestId, String oldDimKey, BlockPos oldPos, String newDimKey, BlockPos newPos) {
        if (ownerUUID == null || chestId == null || chestId.isEmpty() || oldPos == null || newPos == null) return;
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) {
            registerChest(ownerUUID, chestId, newDimKey, newPos);
            return;
        }
        List<ChestLocation> locations = chests.get(chestId);
        if (locations == null) {
            registerChest(ownerUUID, chestId, newDimKey, newPos);
            return;
        }
        ChestLocation newLocation = new ChestLocation(newDimKey, newPos);
        synchronized (locations) {
            locations.removeIf(loc -> loc.dimensionKey.equals(oldDimKey) && loc.pos.equals(oldPos));
            if (!locations.contains(newLocation)) locations.add(newLocation);
        }
    }

    private void registerChest(UUID ownerUUID, String chestId, String dimKey, BlockPos pos) {
        ChestLocation location = new ChestLocation(dimKey, pos);
        List<ChestLocation> locations = playerChests.computeIfAbsent(ownerUUID, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chestId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (locations) {
            if (!locations.contains(location)) locations.add(location);
        }
    }

    public Set<String> getPlayerChestIds(UUID ownerUUID) {
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) return Collections.emptySet();
        return new HashSet<>(chests.keySet());
    }

    public List<ChestLocation> getChestLocations(UUID ownerUUID, String chestId) {
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) return Collections.emptyList();
        List<ChestLocation> locations = chests.get(chestId);
        return locations != null ? new ArrayList<>(locations) : Collections.emptyList();
    }

    public List<RegisteredChest> getAllRegisteredChests() {
        List<RegisteredChest> result = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, List<ChestLocation>>> ownerEntry : playerChests.entrySet()) {
            for (Map.Entry<String, List<ChestLocation>> chestEntry : ownerEntry.getValue().entrySet()) {
                for (ChestLocation location : chestEntry.getValue()) {
                    result.add(new RegisteredChest(ownerEntry.getKey(), chestEntry.getKey(), location));
                }
            }
        }
        return result;
    }

    public BaseChestBlockEntity findChest(UUID ownerUUID, String chestId, ServerLevel currentLevel) {
        if (ownerUUID == null || chestId == null || currentLevel == null) return null;
        MinecraftServer server = currentLevel.getServer();
        if (server == null) return null;
        for (ChestLocation loc : getChestLocations(ownerUUID, chestId)) {
            net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(loc.dimensionKey);
            if (dimLoc == null) continue;
            ServerLevel targetLevel = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    dimLoc
            ));
            if (targetLevel == null || !targetLevel.isLoaded(loc.pos)) continue;
            BaseChestBlockEntity be = HomesteadChestAccess.resolve(targetLevel.getBlockEntity(loc.pos));
            if (be != null && be.getChestId().equals(chestId)) {
                return be;
            }
            be = MovingChestRegistry.findChest(loc.dimensionKey, loc.pos, chestId);
            if (be != null && ownerUUID.equals(be.getOwnerUUID())) return be;
        }
        return null;
    }

    public BaseChestBlockEntity findChestByUuid(UUID ownerUUID, UUID chestUUID, ServerLevel currentLevel) {
        if (ownerUUID == null || chestUUID == null || currentLevel == null) return null;
        MinecraftServer server = currentLevel.getServer();
        if (server == null) return null;
        for (RegisteredChest registered : getAllRegisteredChests()) {
            if (!ownerUUID.equals(registered.ownerUUID())) continue;
            ChestLocation loc = registered.location();
            net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(loc.dimensionKey);
            if (dimLoc == null) continue;
            ServerLevel targetLevel = server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    dimLoc
            ));
            if (targetLevel == null || !targetLevel.isLoaded(loc.pos)) continue;
            BaseChestBlockEntity be = HomesteadChestAccess.resolve(targetLevel.getBlockEntity(loc.pos));
            if (be != null && ownerUUID.equals(be.getOwnerUUID()) && chestUUID.equals(be.getChestUUID())) {
                return be;
            }
            be = MovingChestRegistry.findChest(loc.dimensionKey, loc.pos, registered.chestId());
            if (be != null && ownerUUID.equals(be.getOwnerUUID()) && chestUUID.equals(be.getChestUUID())) return be;
        }
        return null;
    }

    public void clear() {
        playerChests.clear();
    }

    public String generateNextChestId(UUID ownerUUID) {
        String prefix = "chest_";
        int maxNum = 0;
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests != null) {
            for (String id : chests.keySet()) {
                if (!id.startsWith(prefix)) continue;
                try {
                    maxNum = Math.max(maxNum, Integer.parseInt(id.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return prefix + (maxNum + 1);
    }
}
