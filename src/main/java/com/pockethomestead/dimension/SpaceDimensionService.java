package com.pockethomestead.dimension;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.util.Constants;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Optional;

public final class SpaceDimensionService {
    private static final SpaceDimensionService INSTANCE = new SpaceDimensionService();

    private SpaceDimensionService() {
    }

    public static SpaceDimensionService getInstance() {
        return INSTANCE;
    }

    public ServerLevel loadOrCreate(MinecraftServer server, SpaceData space) {
        ServerLevel loaded = server.getLevel(space.getDimensionKey());
        if (loaded != null) {
            return loaded;
        }

        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        ChunkGenerator generator = createGenerator(server, space);
        DimensionType type = createDimensionType(server, space);

        ServerLevel level = registry.createDynamicDimension(space.getDimensionId(), generator, type);
        if (level == null) {
            throw new IllegalStateException("无法加载空间维度: " + space.getDimensionId());
        }
        return level;
    }

    public ServerLevel loadExisting(MinecraftServer server, SpaceData space) {
        ServerLevel loaded = server.getLevel(space.getDimensionKey());
        if (loaded != null) {
            return loaded;
        }

        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        ChunkGenerator generator = createGenerator(server, space);
        DimensionType type = createDimensionType(server, space);

        ServerLevel level = registry.loadDynamicDimension(space.getDimensionId(), generator, type);
        if (level == null) {
            throw new IllegalStateException("无法加载已有空间维度: " + space.getDimensionId());
        }
        return level;
    }

    public boolean unload(MinecraftServer server, SpaceData space) {
        return DynamicDimensionRegistry.from(server).unloadDynamicDimension(space.getDimensionId(), null);
    }

    public boolean delete(MinecraftServer server, SpaceData space) {
        return DynamicDimensionRegistry.from(server).deleteDynamicDimension(space.getDimensionId(), null);
    }

    public boolean isSpaceDimension(ResourceKey<Level> dimension) {
        return dimension.location().getNamespace().equals(PocketHomestead.MODID)
                && dimension.location().getPath().startsWith("space_");
    }

    public boolean isSpaceDimension(Level level) {
        return isSpaceDimension(level.dimension());
    }

    public Optional<SpaceData> findByDimension(Level level) {
        // 仅依赖 SpaceManager 的 dimensionIndex；stream fallback 会掩盖索引不一致的 bug
        return Optional.ofNullable(SpaceManager.getInstance().getSpaceByDimension(level.dimension().location()));
    }

    public BlockPos getSpawnPos(SpaceData space) {
        int y = switch (space.getTerrainType()) {
            case NATURAL -> 60;
            case FLAT, SUPERFLAT -> 65;
        };
        return new BlockPos(0, y, 0);
    }

    public BlockPos prepareSafeSpawn(ServerLevel level, SpaceData space) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (generator instanceof PocketChunkGenerator pocketGenerator) {
            int surfaceY = pocketGenerator.getSurfaceY(space, 0, 0);
            return new BlockPos(0, surfaceY + 2, 0);
        }
        int surfaceY = generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
        return new BlockPos(0, Math.max(surfaceY + 1, level.getSeaLevel() + 1), 0);
    }

    public void clearOutsideBoundary(ServerLevel level, SpaceData space) {
        if (level == null || space == null || space.isInfinite()) return;
        int halfW = space.getWidth() / 2;
        int halfD = space.getDepth() / 2;
        int minX = -halfW - 3;
        int maxX = halfW + 2;
        int minZ = -halfD - 3;
        int maxZ = halfD + 2;
        int minY = Math.max(level.getMinBuildHeight(), Constants.BEDROCK_LAYER_Y);
        int maxY = Math.min(level.getMaxBuildHeight(), Constants.WORLD_MAX_Y);
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int columns = 0;
        int changed = 0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x >= -halfW && x < halfW && z >= -halfD && z < halfD) continue;
                columns++;
                for (int y = minY; y < maxY; y++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).isAir()) continue;
                    if (level.setBlock(pos, air, 2)) changed++;
                }
            }
        }
        PocketHomestead.LOGGER.info("口袋空间边界外侧清理完成: space={} columns={} changed={}",
                space.getSpaceId(), columns, changed);
    }

    public ChunkGenerator buildGenerator(MinecraftServer server, SpaceData space) {
        return createGenerator(server, space);
    }

    public DimensionType buildDimensionType(MinecraftServer server, SpaceData space) {
        return createDimensionType(server, space);
    }

    ChunkGenerator createGenerator(MinecraftServer server, SpaceData space) {
        if (space.isInfinite()) {
            ServerLevel src = server.getLevel(sourceDimKey(space));
            if (src == null) src = server.overworld();
            return copyGenerator(server, src.getChunkSource().getGenerator());
        }
        var biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        ResourceLocation biomeId = ResourceLocation.parse(space.getBiome());
        var biomeKey = ResourceKey.create(Registries.BIOME, biomeId);
        var biome = biomeRegistry.getHolder(biomeKey).orElseGet(() -> biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        return new PocketChunkGenerator(new FixedBiomeSource(biome), server.overworld().getSeed(), space.getSpaceId());
    }

    private ChunkGenerator copyGenerator(MinecraftServer server, ChunkGenerator original) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        try {
            Tag encoded = ChunkGenerator.CODEC.encodeStart(ops, original).getOrThrow();
            return ChunkGenerator.CODEC.parse(ops, encoded).getOrThrow();
        } catch (RuntimeException e) {
            // Codec 编解码失败时（第三方 ChunkGenerator 未暴露完整 CODEC 等）回退到 PocketChunkGenerator
            PocketHomestead.LOGGER.warn("Codec 深拷贝源维度 generator 失败，回退到 PocketChunkGenerator", e);
            var biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
            var biome = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
            return new PocketChunkGenerator(new FixedBiomeSource(biome), server.overworld().getSeed());
        }
    }

    DimensionType createDimensionType(MinecraftServer server, SpaceData space) {
        ServerLevel src = server.getLevel(sourceDimKey(space));
        if (src == null) src = server.overworld();
        return copyDimensionType(src.dimensionType());
    }

    private DimensionType copyDimensionType(DimensionType source) {
        return new DimensionType(
                source.fixedTime(),
                source.hasSkyLight(),
                source.hasCeiling(),
                source.ultraWarm(),
                source.natural(),
                source.coordinateScale(),
                source.bedWorks(),
                source.respawnAnchorWorks(),
                source.minY(),
                source.height(),
                source.logicalHeight(),
                source.infiniburn(),
                source.effectsLocation(),
                source.ambientLight(),
                source.monsterSettings()
        );
    }

    private ResourceKey<Level> sourceDimKey(SpaceData space) {
        ResourceLocation loc = space.getSourceDimension() != null
                ? space.getSourceDimension()
                : ResourceLocation.parse("minecraft:overworld");
        return ResourceKey.create(Registries.DIMENSION, loc);
    }
}
