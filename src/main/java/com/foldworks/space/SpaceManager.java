package com.foldworks.space;

import com.foldworks.Foldworks;
import com.foldworks.dimension.ProductionSpaceManager;
import com.foldworks.dimension.SpaceDimensionService;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class SpaceManager {
    private static volatile SpaceManager instance;
    private final Map<UUID, SpaceData> spaces = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, SpaceData> dimensionIndex = new ConcurrentHashMap<>();
    private final Map<UUID, SpacePermission> ownerPermissions = new ConcurrentHashMap<>();

    private SpaceManager() {}
    public static SpaceManager getInstance() {
        if (instance == null) {
            synchronized (SpaceManager.class) {
                if (instance == null) instance = new SpaceManager();
            }
        }
        return instance;
    }

    public SpaceData createSpace(MinecraftServer server, UUID ownerId, int width, int height, int depth,
                                 SpaceData.TerrainType terrainType, String biome, ResourceLocation sourceDimension,
                                 boolean mobSpawning, boolean structureGeneration, boolean infinite, float terrainAmplitude) {
        int max = com.foldworks.config.ModConfig.MAX_SPACES_PER_PLAYER.get();
        if (max > 0) {
            long owned = spaces.values().stream().filter(s -> s.isOwner(ownerId)).count();
            if (owned >= max) {
                throw new SpaceLimitExceededException(max);
            }
        }
        // 夹紧尺寸到安全范围，防止恶意/误传参数导致 ProductionSpaceChunkGenerator 边界墙循环到世界边界
        int clampedWidth = Math.max(16, Math.min(width, 512));
        int clampedHeight = Math.max(16, Math.min(height, 512));
        int clampedDepth = Math.max(16, Math.min(depth, 512));
        UUID spaceId = UUID.randomUUID();
        // 随机群系（或无效）时，按所选维度的可用群系解析
        String resolvedBiome = (biome == null || biome.isBlank() || biome.equals("random"))
                ? randomBiomeFromDimension(server, sourceDimension)
                : biome;
        SpaceData space = new SpaceData(spaceId, ownerId, clampedWidth, clampedHeight, clampedDepth,
                terrainType, resolvedBiome, sourceDimension, mobSpawning, structureGeneration, infinite, terrainAmplitude);
        space.getPermission().copyFrom(ownerPermission(ownerId));
        spaces.put(spaceId, space);
        dimensionIndex.put(space.getDimensionId(), space);
        SpaceDimensionService.getInstance().loadOrCreate(server, space);
        SpaceStorage.markDirty();
        return space;
    }

    private String randomBiomeFromDimension(MinecraftServer server, ResourceLocation sourceDimension) {
        try {
            ResourceLocation loc = sourceDimension != null ? sourceDimension : ResourceLocation.parse("minecraft:overworld");
            ServerLevel src = server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
            if (src != null) {
                List<String> ids = src.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                        .map(h -> h.unwrapKey().orElse(null))
                        .filter(Objects::nonNull)
                        .map(k -> k.location().toString())
                        .toList();
                // 复用 server 随机源而非每次 new Random()，保证可复现且无对象分配
                if (!ids.isEmpty()) return ids.get(server.overworld().getRandom().nextInt(ids.size()));
            }
        } catch (Exception e) {
            Foldworks.LOGGER.warn("随机群系解析失败，回退 plains", e);
        }
        return "minecraft:plains";
    }

    public SpaceData getSpace(UUID spaceId) { return spaces.get(spaceId); }
    public List<SpaceData> getAccessibleSpaces(UUID playerId) { return spaces.values().stream().filter(s -> s.canView(playerId)).toList(); }
    public List<SpaceData> getOwnedSpaces(UUID playerId) { return spaces.values().stream().filter(s -> s.isOwner(playerId)).toList(); }
    public Collection<SpaceData> getAllSpaces() { return spaces.values(); }

    public void clearSpaces() {
        spaces.clear();
        dimensionIndex.clear();
        ownerPermissions.clear();
        Foldworks.LOGGER.info("清空空间缓存");
    }

    public boolean deleteSpace(MinecraftServer server, UUID spaceId, UUID requesterId) {
        SpaceData space = spaces.get(spaceId);
        if (space == null || !space.isOwner(requesterId)) return false;

        // 驱逐维度中的玩家
        ServerLevel level = server.getLevel(space.getDimensionKey());
        if (level != null) {
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                ProductionSpaceManager.getInstance().exitToReturnPosition(player);
            }
        }

        SpaceChunkLoadingManager.getInstance().remove(server, space);
        space.setChunkLoadingEnabled(false);
        SpaceDimensionService.getInstance().delete(server, space);
        // 标记删除使 BaseChestBlockEntity 的缓存失效，避免残留引用导致权限检查用过时数据
        space.markDeleted();
        spaces.remove(spaceId);
        dimensionIndex.remove(space.getDimensionId());
        SpaceStorage.markDirty();
        return true;
    }

    public void addImportedSpace(MinecraftServer server, SpaceData space) {
        if (space == null) return;
        space.getPermission().copyFrom(ownerPermission(space.getOwnerId()));
        spaces.put(space.getSpaceId(), space);
        dimensionIndex.put(space.getDimensionId(), space);
        if (server != null) SpaceDimensionService.getInstance().loadExisting(server, space);
        SpaceStorage.markDirty();
    }

    public void loadSpaces(Collection<SpaceData> loaded) {
        spaces.clear();
        dimensionIndex.clear();
        for (SpaceData s : loaded) {
            spaces.put(s.getSpaceId(), s);
            dimensionIndex.put(s.getDimensionId(), s);
        }
        Foldworks.LOGGER.info("加载了 {} 个空间", spaces.size());
    }

    public SpacePermission ownerPermission(UUID ownerId) {
        if (ownerId == null) return new SpacePermission();
        return ownerPermissions.computeIfAbsent(ownerId, ignored -> new SpacePermission());
    }

    public void updateOwnerPermission(UUID ownerId, Consumer<SpacePermission> updater) {
        if (ownerId == null || updater == null) return;
        SpacePermission permission = ownerPermission(ownerId);
        updater.accept(permission);
        applyOwnerPermission(ownerId);
        SpaceStorage.markDirty();
    }

    public void loadOwnerPermissions(Map<UUID, SpacePermission> loaded) {
        ownerPermissions.clear();
        if (loaded != null) {
            for (Map.Entry<UUID, SpacePermission> entry : loaded.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                SpacePermission copy = new SpacePermission();
                copy.copyFrom(entry.getValue());
                ownerPermissions.put(entry.getKey(), copy);
            }
        }
        if (ownerPermissions.isEmpty()) {
            for (SpaceData space : spaces.values()) {
                ownerPermissions.computeIfAbsent(space.getOwnerId(), ignored -> {
                    SpacePermission copy = new SpacePermission();
                    copy.copyFrom(space.getPermission());
                    return copy;
                });
            }
        }
        for (UUID ownerId : ownerPermissions.keySet()) applyOwnerPermission(ownerId);
    }

    public Map<UUID, SpacePermission> ownerPermissionsSnapshot() {
        Map<UUID, SpacePermission> copy = new LinkedHashMap<>();
        for (Map.Entry<UUID, SpacePermission> entry : ownerPermissions.entrySet()) {
            SpacePermission permission = new SpacePermission();
            permission.copyFrom(entry.getValue());
            copy.put(entry.getKey(), permission);
        }
        return copy;
    }

    private void applyOwnerPermission(UUID ownerId) {
        SpacePermission permission = ownerPermission(ownerId);
        for (SpaceData space : spaces.values()) {
            if (!space.isOwner(ownerId)) continue;
            space.getPermission().copyFrom(permission);
            if (!space.canEnableOfflineSimulation()) space.setOfflineSimulationEnabled(false);
        }
    }

    public boolean setChunkLoadingEnabled(MinecraftServer server, UUID spaceId, boolean enabled) {
        SpaceData space = spaces.get(spaceId);
        if (space == null) return false;

        if (!enabled) {
            space.setChunkLoadingEnabled(false);
            SpaceChunkLoadingManager.getInstance().remove(server, space);
            SpaceStorage.markDirty();
            return true;
        }

        if (!space.canEnableChunkLoading()) {
            space.setChunkLoadingEnabled(false);
            SpaceChunkLoadingManager.getInstance().remove(server, space);
            SpaceStorage.markDirty();
            return false;
        }

        space.setChunkLoadingEnabled(true);
        try {
            SpaceChunkLoadingManager.getInstance().apply(server, space);
            SpaceStorage.markDirty();
            return true;
        } catch (RuntimeException e) {
            Foldworks.LOGGER.error("开启空间常加载失败: {}", space.getDimensionId(), e);
            space.setChunkLoadingEnabled(false);
            SpaceChunkLoadingManager.getInstance().remove(server, space);
            SpaceStorage.markDirty();
            return false;
        }
    }

    public SpaceData getSpaceByDimension(ResourceLocation dimensionId) {
        return dimensionIndex.get(dimensionId);
    }
}
