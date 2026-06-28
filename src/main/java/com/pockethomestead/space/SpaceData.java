package com.pockethomestead.space;

import com.pockethomestead.PocketHomestead;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class SpaceData {

    public enum TerrainType {
        SUPERFLAT, FLAT, NATURAL
    }

    private final UUID spaceId;
    private final UUID ownerId;
    private final ResourceLocation dimensionId;
    private final int width;
    private final int height;
    private final int depth;
    private final TerrainType terrainType;
    private final String biome;
    private final ResourceLocation sourceDimension;
    private final boolean mobSpawning;
    private final boolean structureGeneration;
    private final boolean infinite;
    private final float terrainAmplitude; // 0=平缓 .. 1=陡峭（缩放自然地形噪声振幅）
    private SpacePermission permission;
    private String name;
    private boolean offlineSimulationEnabled;

    public SpaceData(UUID spaceId, UUID ownerId, int width, int height, int depth,
                     TerrainType terrainType, String biome, ResourceLocation sourceDimension,
                     boolean mobSpawning, boolean structureGeneration, boolean infinite, float terrainAmplitude) {
        this(spaceId, ownerId, defaultDimensionId(spaceId), width, height, depth,
                terrainType, biome, sourceDimension, mobSpawning, structureGeneration, infinite, terrainAmplitude);
    }

    public SpaceData(UUID spaceId, UUID ownerId, ResourceLocation dimensionId,
                     int width, int height, int depth,
                     TerrainType terrainType, String biome, ResourceLocation sourceDimension,
                     boolean mobSpawning, boolean structureGeneration, boolean infinite, float terrainAmplitude) {
        this.spaceId = spaceId;
        this.ownerId = ownerId;
        this.dimensionId = dimensionId;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.terrainType = terrainType;
        this.biome = biome;
        this.sourceDimension = sourceDimension;
        this.mobSpawning = mobSpawning;
        this.structureGeneration = structureGeneration;
        this.infinite = infinite;
        this.terrainAmplitude = terrainAmplitude;
        this.permission = new SpacePermission();
        this.name = "Pocket-" + spaceId.toString().substring(0, 8);
    }

    public UUID getSpaceId() { return spaceId; }
    public UUID getOwnerId() { return ownerId; }
    public ResourceLocation getDimensionId() { return dimensionId; }
    public ResourceKey<Level> getDimensionKey() { return ResourceKey.create(Registries.DIMENSION, dimensionId); }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public TerrainType getTerrainType() { return terrainType; }
    public String getBiome() { return biome; }
    public ResourceLocation getSourceDimension() { return sourceDimension; }
    public boolean isMobSpawning() { return mobSpawning; }
    public boolean isStructureGeneration() { return structureGeneration; }
    public boolean isInfinite() { return infinite; }
    public float getTerrainAmplitude() { return terrainAmplitude; }
    public SpacePermission getPermission() { return permission; }
    public String getName() { return name; }
    public boolean isOfflineSimulationEnabled() { return offlineSimulationEnabled; }

    public void setName(String name) { this.name = name; }
    public boolean canEnableOfflineSimulation() {
        SpacePermission.AccessMode mode = permission.getMode();
        return mode == SpacePermission.AccessMode.PRIVATE || mode == SpacePermission.AccessMode.WHITELIST;
    }
    public void setOfflineSimulationEnabled(boolean enabled) {
        this.offlineSimulationEnabled = enabled && canEnableOfflineSimulation();
    }
    public boolean isOwner(UUID playerId) { return ownerId.equals(playerId); }
    public boolean canAccess(UUID playerId) { return can(playerId, SpacePermission.AccessLevel.USE); }
    public boolean canView(UUID playerId) { return can(playerId, SpacePermission.AccessLevel.VIEW); }
    public boolean can(UUID playerId, SpacePermission.AccessLevel required) { return permission.can(playerId, isOwner(playerId), required); }

    public static ResourceLocation defaultDimensionId(UUID spaceId) {
        return ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "space_" + spaceId.toString().replace('-', '_'));
    }
}
