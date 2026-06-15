package com.pockethomestead.space;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.dimension.PocketDimensionManager;
import com.pockethomestead.dimension.SpaceDimensionService;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpaceManager {
    private static volatile SpaceManager instance;
    private final Map<UUID, SpaceData> spaces = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, SpaceData> dimensionIndex = new ConcurrentHashMap<>();

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
        UUID spaceId = UUID.randomUUID();
        // 随机群系（或无效）时，按所选维度的可用群系解析
        String resolvedBiome = (biome == null || biome.isBlank() || biome.equals("random"))
                ? randomBiomeFromDimension(server, sourceDimension)
                : biome;
        SpaceData space = new SpaceData(spaceId, ownerId, width, height, depth,
                terrainType, resolvedBiome, sourceDimension, mobSpawning, structureGeneration, infinite, terrainAmplitude);
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
                if (!ids.isEmpty()) return ids.get(new Random().nextInt(ids.size()));
            }
        } catch (Exception e) {
            PocketHomestead.LOGGER.warn("随机群系解析失败，回退 plains", e);
        }
        return "minecraft:plains";
    }

    public SpaceData getSpace(UUID spaceId) { return spaces.get(spaceId); }
    public List<SpaceData> getAccessibleSpaces(UUID playerId) { return spaces.values().stream().filter(s -> s.canAccess(playerId)).toList(); }
    public List<SpaceData> getOwnedSpaces(UUID playerId) { return spaces.values().stream().filter(s -> s.isOwner(playerId)).toList(); }
    public Collection<SpaceData> getAllSpaces() { return spaces.values(); }

    public void clearSpaces() {
        spaces.clear();
        dimensionIndex.clear();
        PocketHomestead.LOGGER.info("清空口袋空间缓存");
    }

    public boolean deleteSpace(MinecraftServer server, UUID spaceId, UUID requesterId) {
        SpaceData space = spaces.get(spaceId);
        if (space == null || !space.isOwner(requesterId)) return false;

        // 驱逐维度中的玩家
        ServerLevel level = server.getLevel(space.getDimensionKey());
        if (level != null) {
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                PocketDimensionManager.getInstance().exitToReturnPosition(player);
            }
        }

        SpaceDimensionService.getInstance().delete(server, space);
        spaces.remove(spaceId);
        dimensionIndex.remove(space.getDimensionId());
        SpaceItemRegistry.removeSpace(spaceId);
        SpaceStorage.markDirty();
        PocketHomestead.LOGGER.debug("删除空间: {}", spaceId);
        return true;
    }

    public void loadSpaces(Collection<SpaceData> loaded) {
        spaces.clear();
        dimensionIndex.clear();
        for (SpaceData s : loaded) {
            spaces.put(s.getSpaceId(), s);
            dimensionIndex.put(s.getDimensionId(), s);
        }
        PocketHomestead.LOGGER.info("加载了 {} 个口袋空间", spaces.size());
    }

    public SpaceData getSpaceByDimension(ResourceLocation dimensionId) {
        return dimensionIndex.get(dimensionId);
    }
}
