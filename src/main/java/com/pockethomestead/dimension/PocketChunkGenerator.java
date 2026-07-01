package com.pockethomestead.dimension;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.util.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PocketChunkGenerator extends ChunkGenerator {
    // 平坦地表：基岩(1) + 石头(122) + 泥土(4) + 草(1) → 地表 y = minY + 127
    private static final int FLAT_SURFACE_Y = Constants.WORLD_MIN_Y + 127; // = 63
    private static final int FLAT_DIRT_LAYERS = 4;

    // 自然地形：以 base 为中心，按 terrainAmplitude 缩放噪声振幅
    private static final int NATURAL_BASE_Y = 56;
    private static final int NATURAL_MAX_AMP = 40;
    private static final int NATURAL_MIN_Y = -56;
    private static final int NATURAL_MAX_Y = 120;

    // 封边清理高度上限（地表 + 余量，覆盖树木等装饰）
    private static final int SEAL_TOP_Y = NATURAL_MAX_Y + 32;

    public static final MapCodec<PocketChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    Codec.LONG.fieldOf("seed").orElse(0L).forGetter(g -> g.seed),
                    Codec.STRING.optionalFieldOf("space_id", "").forGetter(g -> g.spaceId == null ? "" : g.spaceId.toString())
            ).apply(instance, (biomeSource, seed, spaceId) -> new PocketChunkGenerator(
                    biomeSource,
                    seed,
                    parseSpaceId(spaceId)
            ))
    );

    /** 容错解析 space_id：格式非法时返回 null 而非抛异常，避免 Codec 解析失败导致维度不可用 */
    private static UUID parseSpaceId(String spaceId) {
        if (spaceId == null || spaceId.isBlank()) return null;
        try {
            return UUID.fromString(spaceId);
        } catch (IllegalArgumentException e) {
            PocketHomestead.LOGGER.warn("无法解析 space_id '{}', 维度将无空间关联", spaceId);
            return null;
        }
    }

    private final long seed;
    private final UUID spaceId;

    public PocketChunkGenerator(BiomeSource biomeSource, long seed) {
        this(biomeSource, seed, null);
    }

    public PocketChunkGenerator(BiomeSource biomeSource, long seed, UUID spaceId) {
        super(biomeSource);
        this.seed = seed;
        this.spaceId = spaceId;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structures, RandomState random, ChunkAccess chunk) {
        // 地形完全在 fillFromNoise 中生成
    }

    private SpaceData getSpace() {
        return spaceId == null ? null : SpaceManager.getInstance().getSpace(spaceId);
    }

    public int getSurfaceY(SpaceData space, int x, int z) {
        return switch (space.getTerrainType()) {
            case SUPERFLAT, FLAT -> FLAT_SURFACE_Y;
            case NATURAL -> naturalSurfaceY(space, x, z);
        };
    }

    private int naturalSurfaceY(SpaceData space, int x, int z) {
        double continent = fractalNoise(x * 0.018, z * 0.018, 4, 0.52);
        double detail = fractalNoise((x + 911) * 0.055, (z - 353) * 0.055, 3, 0.48);
        double ridge = 1.0 - Math.abs(fractalNoise((x - 1207) * 0.028, (z + 2041) * 0.028, 3, 0.55));
        double combo = continent * 0.68 + detail * 0.22 + ridge * 0.10;
        double edge = edgeFalloff(space, x, z);
        // terrainAmplitude 直接缩放噪声值相对大小：0=平缓，1=陡峭
        int y = NATURAL_BASE_Y + (int) Math.round(combo * NATURAL_MAX_AMP * space.getTerrainAmplitude() * edge);
        return clamp(y, NATURAL_MIN_Y, NATURAL_MAX_Y);
    }

    private double edgeFalloff(SpaceData space, int x, int z) {
        int halfW = space.getWidth() / 2;
        int halfD = space.getDepth() / 2;
        int dist = Math.min(Math.min(x + halfW, halfW - 1 - x), Math.min(z + halfD, halfD - 1 - z));
        return clamp(dist / 10.0, 0.35, 1.0);
    }

    // 单列方块：基岩 / 表层 / 次表层 / 深层；平坦走固定分层，自然走 biome 配色
    private BlockState stateAt(SpaceData space, int y, int surfaceY) {
        if (y == Constants.BEDROCK_LAYER_Y) return Blocks.BEDROCK.defaultBlockState();
        return switch (space.getTerrainType()) {
            case NATURAL -> {
                if (y == surfaceY) yield surfaceState(space.getBiome());
                if (surfaceY - y <= 3) yield subSurfaceState(space.getBiome());
                yield deepState(space.getBiome());
            }
            case FLAT, SUPERFLAT -> {
                if (y == surfaceY) yield Blocks.GRASS_BLOCK.defaultBlockState();
                if (y >= surfaceY - FLAT_DIRT_LAYERS) yield Blocks.DIRT.defaultBlockState();
                yield Blocks.STONE.defaultBlockState();
            }
        };
    }

    private static BlockState surfaceState(String biomeId) {
        String path = biomePath(biomeId);
        return switch (path) {
            case "desert", "badlands", "eroded_badlands", "wooded_badlands", "beach", "snowy_beach" -> Blocks.SAND.defaultBlockState();
            case "snowy_plains", "ice_spikes", "snowy_taiga", "snowy_slopes", "frozen_peaks", "jagged_peaks", "grove" -> Blocks.SNOW_BLOCK.defaultBlockState();
            case "mushroom_fields" -> Blocks.MYCELIUM.defaultBlockState();
            case "warped_forest" -> Blocks.WARPED_NYLIUM.defaultBlockState();
            case "crimson_forest" -> Blocks.CRIMSON_NYLIUM.defaultBlockState();
            case "soul_sand_valley" -> Blocks.SOUL_SOIL.defaultBlockState();
            case "nether_wastes", "basalt_deltas" -> Blocks.NETHERRACK.defaultBlockState();
            case "end_barrens", "end_highlands", "end_midlands", "the_end", "small_end_islands" -> Blocks.END_STONE.defaultBlockState();
            default -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }

    private static BlockState subSurfaceState(String biomeId) {
        String path = biomePath(biomeId);
        return switch (path) {
            case "desert", "badlands", "eroded_badlands", "wooded_badlands", "beach", "snowy_beach" -> Blocks.SANDSTONE.defaultBlockState();
            case "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas" -> Blocks.NETHERRACK.defaultBlockState();
            case "end_barrens", "end_highlands", "end_midlands", "the_end", "small_end_islands" -> Blocks.END_STONE.defaultBlockState();
            default -> Blocks.DIRT.defaultBlockState();
        };
    }

    private static BlockState deepState(String biomeId) {
        String path = biomePath(biomeId);
        return switch (path) {
            case "nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas" -> Blocks.NETHERRACK.defaultBlockState();
            case "end_barrens", "end_highlands", "end_midlands", "the_end", "small_end_islands" -> Blocks.END_STONE.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    private static String biomePath(String biomeId) {
        return biomeId.contains(":") ? biomeId.substring(biomeId.indexOf(':') + 1) : biomeId;
    }

    private static boolean insideSpace(SpaceData space, int x, int z) {
        int halfWidth = space.getWidth() / 2;
        int halfDepth = space.getDepth() / 2;
        return x >= -halfWidth && x < halfWidth && z >= -halfDepth && z < halfDepth;
    }

    private static boolean onBoundary(SpaceData space, int x, int z) {
        int halfWidth = space.getWidth() / 2;
        int halfDepth = space.getDepth() / 2;
        return (x == -halfWidth || x == halfWidth - 1) && z >= -halfDepth && z < halfDepth
                || (z == -halfDepth || z == halfDepth - 1) && x >= -halfWidth && x < halfWidth;
    }

    private static boolean chunkIntersectsFootprint(SpaceData space, int chunkMinX, int chunkMinZ) {
        int halfW = space.getWidth() / 2;
        int halfD = space.getDepth() / 2;
        return chunkMinX + 15 >= -halfW && chunkMinX < halfW
                && chunkMinZ + 15 >= -halfD && chunkMinZ < halfD;
    }

    private void placeBoundaryWalls(ChunkAccess chunk, int chunkMinX, int chunkMinZ, SpaceData space) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int topY = Constants.WORLD_MAX_Y; // 屏障覆盖整个维度高度（从基岩到建造上限）
        for (int lx = 0; lx < 16; lx++) {
            int x = chunkMinX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = chunkMinZ + lz;
                if (!onBoundary(space, x, z)) continue;
                for (int y = Constants.BEDROCK_LAYER_Y; y < topY; y++) {
                    pos.set(x, y, z);
                    chunk.setBlockState(pos, Blocks.BARRIER.defaultBlockState(), false);
                }
            }
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        SpaceData space = getSpace();
        if (space == null || !space.isMobSpawning()) return;
        // ChunkGenerator.spawnOriginalMobs 是抽象方法，直接调用 NaturalSpawner.spawnMobsForChunkGeneration
        // 这与 NoiseBasedChunkGenerator 的实现方式一致
        ChunkPos chunkPos = region.getCenter();
        Holder<Biome> biome = region.getBiome(chunkPos.getWorldPosition());
        NaturalSpawner.spawnMobsForChunkGeneration(region, biome, chunkPos, region.getRandom());
    }

    @Override public int getGenDepth() { return Constants.DEFAULT_HEIGHT; }
    @Override public int getSeaLevel() { return FLAT_SURFACE_Y; }
    @Override public int getMinY() { return Constants.BEDROCK_LAYER_Y; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        SpaceData space = getSpace();
        return space != null && insideSpace(space, x, z) ? getSurfaceY(space, x, z) + 1 : getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int minY = getMinY();
        BlockState[] states = new BlockState[getGenDepth()];
        for (int i = 0; i < states.length; i++) states[i] = Blocks.AIR.defaultBlockState();

        SpaceData space = getSpace();
        if (space != null && insideSpace(space, x, z)) {
            int surfaceY = getSurfaceY(space, x, z);
            for (int y = Constants.BEDROCK_LAYER_Y; y <= surfaceY; y++) {
                int index = y - minY;
                if (index < 0 || index >= states.length) continue;
                states[index] = stateAt(space, y, surfaceY);
            }
        }
        return new NoiseColumn(minY, states);
    }

    @Override public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {}
    @Override public void applyCarvers(WorldGenRegion region, long seed, RandomState random, BiomeManager biomeManager, StructureManager structures, ChunkAccess chunk, GenerationStep.Carving carving) {}

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
        SpaceData space = getSpace();
        if (space == null) return CompletableFuture.completedFuture(chunk);

        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();

        // 预计算 biome 对应的地表/次表层/深层 BlockState（空间内 biome 固定，避免每 block 做 String 切分）
        BlockState surfaceSt, subSurfaceSt, deepSt;
        int dirtLayers;
        switch (space.getTerrainType()) {
            case NATURAL -> {
                surfaceSt = surfaceState(space.getBiome());
                subSurfaceSt = subSurfaceState(space.getBiome());
                deepSt = deepState(space.getBiome());
                dirtLayers = 3;
            }
            case FLAT, SUPERFLAT -> {
                surfaceSt = Blocks.GRASS_BLOCK.defaultBlockState();
                subSurfaceSt = Blocks.DIRT.defaultBlockState();
                deepSt = Blocks.STONE.defaultBlockState();
                dirtLayers = FLAT_DIRT_LAYERS;
            }
            default -> throw new IllegalStateException("未知的 terrain type: " + space.getTerrainType());
        }

        for (int lx = 0; lx < 16; lx++) {
            int x = chunkMinX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = chunkMinZ + lz;
                if (!insideSpace(space, x, z)) continue;

                int surfaceY = getSurfaceY(space, x, z);
                for (int y = Constants.BEDROCK_LAYER_Y; y <= surfaceY; y++) {
                    pos.set(x, y, z);
                    BlockState st;
                    if (y == Constants.BEDROCK_LAYER_Y) st = bedrock;
                    else if (y == surfaceY) st = surfaceSt;
                    else if (surfaceY - y <= dirtLayers) st = subSurfaceSt;
                    else st = deepSt;
                    chunk.setBlockState(pos, st, false);
                }
            }
        }

        placeBoundaryWalls(chunk, chunkMinX, chunkMinZ, space);
        return CompletableFuture.completedFuture(chunk);
    }

    // 关闭结构生成时不创建任何结构，杜绝越界建筑；space 为 null 时也不生成
    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templateManager) {
        SpaceData space = getSpace();
        if (space == null || !space.isStructureGeneration()) return;
        super.createStructures(registryAccess, structureState, structureManager, chunk, templateManager);
    }

    // 范围外区块不装饰；边界区块装饰后封边（先清空气、再补屏障）
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        SpaceData space = getSpace();
        if (space == null) { super.applyBiomeDecoration(level, chunk, structureManager); return; }

        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        if (!chunkIntersectsFootprint(space, chunkMinX, chunkMinZ)) {
            return; // 整块在范围外：不装饰
        }
        super.applyBiomeDecoration(level, chunk, structureManager);
        sealChunk(level, chunk, space, chunkMinX, chunkMinZ);
    }

    // 清除 footprint 外列的装饰方块。边界屏障已在 fillFromNoise 的 placeBoundaryWalls 放置，BARRIER 不可破坏，无需重复
    private void sealChunk(WorldGenLevel level, ChunkAccess chunk, SpaceData space, int chunkMinX, int chunkMinZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int lx = 0; lx < 16; lx++) {
            int x = chunkMinX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = chunkMinZ + lz;
                if (insideSpace(space, x, z)) continue;
                for (int y = Constants.BEDROCK_LAYER_Y; y <= SEAL_TOP_Y; y++) {
                    pos.set(x, y, z);
                    chunk.setBlockState(pos, air, false);
                }
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double fractalNoise(double x, double z, int octaves, double persistence) {
        double value = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double max = 0.0;
        for (int i = 0; i < octaves; i++) {
            value += smoothValueNoise(x * frequency, z * frequency, seed + i * 10_391L) * amplitude;
            max += amplitude;
            amplitude *= persistence;
            frequency *= 2.0;
        }
        return value / max;
    }

    private static double smoothValueNoise(double x, double z, long salt) {
        int x0 = fastFloor(x);
        int z0 = fastFloor(z);
        int x1 = x0 + 1;
        int z1 = z0 + 1;
        double tx = fade(x - x0);
        double tz = fade(z - z0);
        double a = randomValue(x0, z0, salt);
        double b = randomValue(x1, z0, salt);
        double c = randomValue(x0, z1, salt);
        double d = randomValue(x1, z1, salt);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    // splitmix64 风格的确定性 hash：用固定常量混合坐标生成伪随机值，常量来自 splitmix64 算法
    private static double randomValue(int x, int z, long salt) {
        long h = salt;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= z * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return ((h >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int fastFloor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
