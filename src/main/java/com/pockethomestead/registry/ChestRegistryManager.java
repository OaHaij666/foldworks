package com.pockethomestead.registry;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局箱子注册表管理器
 *
 * 功能：
 * 1. 跟踪每个玩家放置的所有箱子
 * 2. 根据 玩家UUID + 箱子ID 查找箱子
 * 3. 支持箱子绑定关系查询
 * 4. 线程安全
 */
public class ChestRegistryManager {

    // 单例
    private static final ChestRegistryManager INSTANCE = new ChestRegistryManager();

    public static ChestRegistryManager getInstance() {
        return INSTANCE;
    }

    // 玩家UUID -> (箱子ID -> 箱子位置信息列表)
    // 注意：同一个ID可能对应多个箱子（如果玩家设置了重复ID）
    private final Map<UUID, Map<String, List<ChestLocation>>> playerChests = new ConcurrentHashMap<>();

    // 箱子位置信息
    public static class ChestLocation {
        public final String dimensionKey;  // 维度键（例如 "minecraft:overworld"）
        public final BlockPos pos;
        public final ChestType type;       // SUPPLY 或 PICKUP

        public ChestLocation(String dimensionKey, BlockPos pos, ChestType type) {
            this.dimensionKey = dimensionKey;
            this.pos = pos;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ChestLocation)) return false;
            ChestLocation that = (ChestLocation) o;
            return Objects.equals(dimensionKey, that.dimensionKey) &&
                   Objects.equals(pos, that.pos) &&
                   type == that.type;
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimensionKey, pos, type);
        }
    }

    public enum ChestType {
        SUPPLY,   // 供货箱
        PICKUP    // 取货箱
    }

    /**
     * 注册一个箱子
     */
    public void registerChest(UUID ownerUUID, String chestId, Level level, BlockPos pos, ChestType type) {
        if (ownerUUID == null || chestId == null || chestId.isEmpty()) return;

        String dimKey = level.dimension().location().toString();
        ChestLocation location = new ChestLocation(dimKey, pos, type);

        playerChests.computeIfAbsent(ownerUUID, k -> new ConcurrentHashMap<>())
                   .computeIfAbsent(chestId, k -> new ArrayList<>())
                   .add(location);
    }

    /**
     * 注销一个箱子
     */
    public void unregisterChest(UUID ownerUUID, String chestId, Level level, BlockPos pos, ChestType type) {
        if (ownerUUID == null || chestId == null) return;

        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) return;

        List<ChestLocation> locations = chests.get(chestId);
        if (locations == null) return;

        String dimKey = level.dimension().location().toString();
        locations.removeIf(loc -> loc.dimensionKey.equals(dimKey) &&
                                  loc.pos.equals(pos) &&
                                  loc.type == type);

        // 清理空列表
        if (locations.isEmpty()) {
            chests.remove(chestId);
        }
        if (chests.isEmpty()) {
            playerChests.remove(ownerUUID);
        }
    }

    /**
     * 更新箱子ID（当玩家修改ID时）
     */
    public void updateChestId(UUID ownerUUID, String oldId, String newId, Level level, BlockPos pos, ChestType type) {
        if (ownerUUID == null || oldId == null || newId == null) return;

        // 先注销旧ID
        unregisterChest(ownerUUID, oldId, level, pos, type);
        // 再注册新ID
        registerChest(ownerUUID, newId, level, pos, type);
    }

    /**
     * 获取玩家的所有箱子ID列表（去重）
     */
    public Set<String> getPlayerChestIds(UUID ownerUUID) {
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) return Collections.emptySet();
        return new HashSet<>(chests.keySet());
    }

    /**
     * 获取玩家某个ID的所有箱子位置
     */
    public List<ChestLocation> getChestLocations(UUID ownerUUID, String chestId) {
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) return Collections.emptyList();

        List<ChestLocation> locations = chests.get(chestId);
        return locations != null ? new ArrayList<>(locations) : Collections.emptyList();
    }

    /**
     * 获取玩家某个ID的特定类型箱子（用于绑定）
     * @param ownerUUID 玩家UUID
     * @param chestId 箱子ID
     * @param type 箱子类型
     * @return 箱子位置列表
     */
    public List<ChestLocation> getChestLocationsByType(UUID ownerUUID, String chestId, ChestType type) {
        List<ChestLocation> all = getChestLocations(ownerUUID, chestId);
        List<ChestLocation> filtered = new ArrayList<>();
        for (ChestLocation loc : all) {
            if (loc.type == type) {
                filtered.add(loc);
            }
        }
        return filtered;
    }

    /**
     * 获取玩家所有未绑定的箱子ID（某种类型）
     * @param ownerUUID 玩家UUID
     * @param type 箱子类型
     * @param level 当前世界（用于检查箱子状态）
     * @return 未绑定的箱子ID集合
     */
    public Set<String> getUnboundChestIds(UUID ownerUUID, ChestType type, ServerLevel level) {
        Set<String> unboundIds = new HashSet<>();
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests == null) return unboundIds;

        for (Map.Entry<String, List<ChestLocation>> entry : chests.entrySet()) {
            String chestId = entry.getKey();
            List<ChestLocation> locations = entry.getValue();

            // 检查是否有该类型的未绑定箱子
            for (ChestLocation loc : locations) {
                if (loc.type != type) continue;

                // 跨维度查询箱子
                net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(loc.dimensionKey);
                if (dimLoc == null) continue;

                ServerLevel targetLevel = level.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    dimLoc
                ));

                if (targetLevel != null && targetLevel.getBlockEntity(loc.pos) instanceof BaseChestBlockEntity be) {
                    // 如果箱子未绑定，添加到列表
                    if (be.getBoundTargetId() == null || be.getBoundTargetId().isEmpty()) {
                        unboundIds.add(chestId);
                        break;  // 该ID至少有一个未绑定，不需要继续检查
                    }
                }
            }
        }

        return unboundIds;
    }

    /**
     * 根据绑定ID查找对应的箱子BlockEntity
     * @param ownerUUID 所有者UUID
     * @param boundTargetId 绑定目标ID
     * @param targetType 目标箱子类型
     * @param currentLevel 当前世界（用于跨维度查询）
     * @return 找到的第一个匹配箱子，如果没找到返回null
     */
    public BaseChestBlockEntity findBoundChest(UUID ownerUUID, String boundTargetId, ChestType targetType, ServerLevel currentLevel) {
        List<ChestLocation> locations = getChestLocationsByType(ownerUUID, boundTargetId, targetType);

        for (ChestLocation loc : locations) {
            // 跨维度查询
            net.minecraft.resources.ResourceLocation dimLoc = net.minecraft.resources.ResourceLocation.tryParse(loc.dimensionKey);
            if (dimLoc == null) continue;

            ServerLevel targetLevel = currentLevel.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
            ));

            if (targetLevel != null && targetLevel.getBlockEntity(loc.pos) instanceof BaseChestBlockEntity be) {
                // 检查箱子是否还有效（ID匹配）
                if (be.getChestId().equals(boundTargetId)) {
                    return be;
                }
            }
        }

        return null;
    }

    /**
     * 清空所有数据（用于服务器关闭）
     */
    public void clear() {
        playerChests.clear();
    }

    /**
     * 为玩家生成下一个自动箱子ID
     * 格式：supply_1, supply_2, ... 或 pickup_1, pickup_2, ...
     * @param ownerUUID 玩家UUID
     * @param type 箱子类型
     * @return 自动生成的ID
     */
    public String generateNextChestId(UUID ownerUUID, ChestType type) {
        String prefix = type == ChestType.SUPPLY ? "supply_" : "pickup_";
        int maxNum = 0;
        Map<String, List<ChestLocation>> chests = playerChests.get(ownerUUID);
        if (chests != null) {
            for (String id : chests.keySet()) {
                if (id.startsWith(prefix)) {
                    try {
                        int num = Integer.parseInt(id.substring(prefix.length()));
                        maxNum = Math.max(maxNum, num);
                    } catch (NumberFormatException ignored) {
                        // 非数字后缀的ID跳过
                    }
                }
            }
        }
        return prefix + (maxNum + 1);
    }
}
