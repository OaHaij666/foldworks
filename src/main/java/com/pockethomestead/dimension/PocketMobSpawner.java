package com.pockethomestead.dimension;

import com.pockethomestead.space.SpaceData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 有限口袋空间的补充自然刷怪。
 *
 * 原版自然刷怪按玩家周围 24~128 格抽样；有限空间尺寸较小时，大部分抽样点会落到边界外。
 * 无限空间复制源维度生成器，不受这个限制。这里仅对 PocketChunkGenerator 的有限空间放宽半径，
 * 但仍走 NaturalSpawner.spawnCategoryForPosition，让原版生物/群系/亮度/方块规则负责最终判定。
 */
public final class PocketMobSpawner {
    private static final MobCategory[] CATEGORIES = {
            MobCategory.CREATURE,
            MobCategory.MONSTER,
            MobCategory.AMBIENT,
            MobCategory.WATER_CREATURE,
            MobCategory.WATER_AMBIENT,
            MobCategory.UNDERGROUND_WATER_CREATURE,
            MobCategory.AXOLOTLS
    };

    private PocketMobSpawner() {
    }

    public static void tick(MinecraftServer server) {
        if (server == null || server.overworld().getGameTime() % 40 != 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            SpaceData space = SpaceDimensionService.getInstance().findByDimension(level).orElse(null);
            if (space == null || space.isInfinite() || !space.isMobSpawning()) continue;
            if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) continue;
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            if (!(generator instanceof PocketChunkGenerator)) continue;
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) continue;
            for (ServerPlayer player : players) {
                spawnAroundPlayer(level, space, player);
            }
        }
    }

    private static void spawnAroundPlayer(ServerLevel level, SpaceData space, ServerPlayer player) {
        RandomSource random = level.random;
        int halfW = Math.max(1, space.getWidth() / 2);
        int halfD = Math.max(1, space.getDepth() / 2);
        int maxDistance = Math.max(6, Math.min(64, Math.min(halfW, halfD) - 2));
        int minDistance = Math.min(24, Math.max(4, maxDistance / 3));
        if (maxDistance <= minDistance) minDistance = Math.max(0, maxDistance - 2);

        for (MobCategory category : CATEGORIES) {
            if (nearbyCount(level, player.blockPosition(), maxDistance + 16, category) >= localCap(category, space)) continue;
            for (int attempt = 0; attempt < 3; attempt++) {
                BlockPos pos = randomSurfacePos(level, space, player.blockPosition(), minDistance, maxDistance, random);
                if (pos == null) continue;
                NaturalSpawner.spawnCategoryForPosition(category, level, pos);
                break;
            }
        }
    }

    private static BlockPos randomSurfacePos(ServerLevel level, SpaceData space, BlockPos center, int minDistance, int maxDistance, RandomSource random) {
        int minDistSq = minDistance * minDistance;
        int maxDistSq = maxDistance * maxDistance;
        for (int i = 0; i < 12; i++) {
            int x = center.getX() + random.nextInt(maxDistance * 2 + 1) - maxDistance;
            int z = center.getZ() + random.nextInt(maxDistance * 2 + 1) - maxDistance;
            int dx = x - center.getX();
            int dz = z - center.getZ();
            int distSq = dx * dx + dz * dz;
            if (distSq < minDistSq || distSq > maxDistSq) continue;
            if (!insideSpace(space, x, z)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) continue;
            return new BlockPos(x, y, z);
        }
        return null;
    }

    private static boolean insideSpace(SpaceData space, int x, int z) {
        int halfWidth = space.getWidth() / 2;
        int halfDepth = space.getDepth() / 2;
        return x >= -halfWidth + 1 && x < halfWidth - 1 && z >= -halfDepth + 1 && z < halfDepth - 1;
    }

    private static int nearbyCount(ServerLevel level, BlockPos center, int radius, MobCategory category) {
        AABB box = new AABB(center).inflate(radius, 96, radius);
        return level.getEntitiesOfClass(Mob.class, box, mob -> mob.getType().getCategory() == category).size();
    }

    private static int localCap(MobCategory category, SpaceData space) {
        int areaFactor = Math.max(1, (space.getWidth() * space.getDepth()) / (64 * 64));
        return switch (category) {
            case MONSTER -> 18 * areaFactor;
            case CREATURE -> 8 * areaFactor;
            case AMBIENT -> 6 * areaFactor;
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> 6 * areaFactor;
            default -> 4 * areaFactor;
        };
    }
}
