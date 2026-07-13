package com.foldworks.space;

import com.foldworks.Foldworks;
import com.foldworks.config.ModConfig;
import com.foldworks.dimension.SpaceDimensionService;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

import java.util.UUID;

public final class SpaceChunkLoadingManager {
    private static final SpaceChunkLoadingManager INSTANCE = new SpaceChunkLoadingManager();
    private static final ResourceLocation CONTROLLER_ID =
            ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "space_chunk_loading");
    private static final TicketController CONTROLLER =
            new TicketController(CONTROLLER_ID, SpaceChunkLoadingManager::validateTickets);

    private SpaceChunkLoadingManager() {
    }

    public static SpaceChunkLoadingManager getInstance() {
        return INSTANCE;
    }

    public static void registerTicketControllers(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    public static boolean canEnable(SpaceData space) {
        if (space == null || space.isInfinite()) return false;
        long area = area(space);
        return area > 0 && area <= Math.max(1, ModConfig.SPACE_CHUNK_LOADING_MAX_AREA.get());
    }

    public static long area(SpaceData space) {
        if (space == null) return 0L;
        return (long) Math.max(0, space.getWidth()) * (long) Math.max(0, space.getDepth());
    }

    public void apply(MinecraftServer server, SpaceData space) {
        if (server == null || space == null || !canEnable(space)) return;
        ServerLevel level = SpaceDimensionService.getInstance().loadOrCreate(server, space);
        forceRange(level, space, true);
    }

    public void remove(MinecraftServer server, SpaceData space) {
        if (server == null || space == null) return;
        ServerLevel level = server.getLevel(space.getDimensionKey());
        if (level == null) return;
        forceRange(level, space, false);
    }

    public void reconcile(MinecraftServer server) {
        if (server == null) return;
        boolean dirty = false;
        int applied = 0;
        int disabled = 0;
        for (SpaceData space : SpaceManager.getInstance().getAllSpaces()) {
            if (!space.isChunkLoadingEnabled()) continue;
            if (!canEnable(space)) {
                space.setChunkLoadingEnabled(false);
                remove(server, space);
                dirty = true;
                disabled++;
                continue;
            }
            try {
                apply(server, space);
                applied++;
            } catch (RuntimeException e) {
                Foldworks.LOGGER.error("开启空间常加载失败: {}", space.getDimensionId(), e);
                space.setChunkLoadingEnabled(false);
                remove(server, space);
                dirty = true;
                disabled++;
            }
        }
        if (dirty) SpaceStorage.markDirty();
        if (applied > 0 || disabled > 0) {
            Foldworks.LOGGER.info("空间常加载同步完成：应用 {} 个，关闭 {} 个", applied, disabled);
        }
    }

    public void reset() {
        // NeoForge 的持久化 ticket 属于存档数据；这里不主动清除，只重置运行期入口。
    }

    private static void validateTickets(ServerLevel level, TicketHelper helper) {
        for (UUID spaceId : helper.getEntityTickets().keySet()) {
            SpaceData space = SpaceManager.getInstance().getSpace(spaceId);
            if (space == null
                    || !space.getDimensionKey().equals(level.dimension())
                    || !space.isChunkLoadingEnabled()
                    || !canEnable(space)) {
                helper.removeAllTickets(spaceId);
            }
        }
    }

    private static void forceRange(ServerLevel level, SpaceData space, boolean add) {
        ChunkRange range = ChunkRange.of(space);
        UUID owner = space.getSpaceId();
        for (int cx = range.minChunkX(); cx <= range.maxChunkX(); cx++) {
            for (int cz = range.minChunkZ(); cz <= range.maxChunkZ(); cz++) {
                CONTROLLER.forceChunk(level, owner, cx, cz, add, true);
                if (!add) CONTROLLER.forceChunk(level, owner, cx, cz, false, false);
            }
        }
    }

    private record ChunkRange(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        private static ChunkRange of(SpaceData space) {
            int halfW = space.getWidth() / 2;
            int halfD = space.getDepth() / 2;
            int minBlockX = -halfW;
            int maxBlockX = halfW - 1;
            int minBlockZ = -halfD;
            int maxBlockZ = halfD - 1;
            return new ChunkRange(
                    Math.floorDiv(minBlockX, 16),
                    Math.floorDiv(maxBlockX, 16),
                    Math.floorDiv(minBlockZ, 16),
                    Math.floorDiv(maxBlockZ, 16)
            );
        }
    }
}
