package com.pockethomestead;

import com.pockethomestead.command.PocketHomesteadCommand;
import com.pockethomestead.archive.SpaceArchiveTransferManager;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.dimension.PocketDimensionManager;
import com.pockethomestead.moving.MovingChestRegistry;
import com.pockethomestead.offline.OfflineChestSnapshotStorage;
import com.pockethomestead.permission.AccessControl;
import com.pockethomestead.scheduler.SpaceScheduler;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceChunkLoadingManager;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.space.SpaceStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import com.pockethomestead.registration.ModBlockEntities;
import com.pockethomestead.blockentity.HomesteadChestAccess;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = PocketHomestead.MODID)
public class ModEvents {
    private static final int VANILLA_MOB_CAP_CHUNKS = 17 * 17;
    private static final Map<SpawnCapKey, SpawnCapCounter> pocketSpawnCapCache = new HashMap<>();
    private static long pocketSpawnCapCacheTick = Long.MIN_VALUE;

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.HOMESTEAD_CHEST.get(),
                (be, context) -> {
                    var chest = HomesteadChestAccess.resolve(be);
                    return chest == null ? null : chest.getItemHandler(context);
                }
        );
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.HOMESTEAD_CHEST.get(),
                (be, context) -> {
                    var chest = HomesteadChestAccess.resolve(be);
                    return chest == null ? null : chest.getFluidHandler(context);
                }
        );
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.HOMESTEAD_CHEST.get(),
                (be, context) -> {
                    var chest = HomesteadChestAccess.resolve(be);
                    return chest == null ? null : chest.getEnergyHandler(context);
                }
        );
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        PocketHomesteadCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // 触发 SpaceStorage 加载——dimensions 已在 DynamicDimensionLoadCallback 阶段创建。
        server.overworld().getDataStorage().computeIfAbsent(
                SpaceStorage.factory(),
                "pockethomestead_spaces"
        );
        PocketHomestead.LOGGER.info("口袋空间数据已加载（{} 个空间）", SpaceManager.getInstance().getAllSpaces().size());
        SpaceChunkLoadingManager.getInstance().reconcile(server);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SpaceArchiveTransferManager.reset();
        SpaceScheduler.getInstance().reset();
        SpaceChunkLoadingManager.getInstance().reset();
        MovingChestRegistry.clear();
        clearPocketSpawnCapCache();
        SpaceManager.getInstance().clearSpaces();
        PocketDimensionManager.getInstance().reset();
        SpaceStorage.clearInstance();
        PocketHomestead.LOGGER.info("口袋空间运行时缓存已清理");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SpaceScheduler.getInstance().onServerTick(event);
        PocketDimensionManager.getInstance().onServerTick(event.getServer());
        SpaceArchiveTransferManager.tick(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!PocketDimensionManager.getInstance().isPocketDimension(event.getDimension())) return;

        SpaceData space = SpaceManager.getInstance().getSpaceByDimension(event.getDimension().location());
        if (space != null && space.canAccess(player.getUUID())) return;

        event.setCanceled(true);
        denyPocketEntry(player);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        ejectIfUnauthorizedPocketResident(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        ejectIfUnauthorizedPocketResident(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.server.execute(() -> ejectIfUnauthorizedPocketResident(player));
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level
                && event.getPlayer() instanceof ServerPlayer player
                && !canModifySpace(player, level)) {
            event.setCanceled(true);
            AccessControl.deny(player);
            return;
        }
        if (event.getLevel() instanceof Level level && !level.isClientSide) {
            var chest = HomesteadChestAccess.resolve(level.getBlockEntity(event.getPos()));
            if (chest != null) {
                chest.markDestroyedForOfflineSnapshot();
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    OfflineChestSnapshotStorage.get(serverLevel.getServer())
                            .deleteSnapshot(level.dimension().location().toString(), event.getPos());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level
                && event.getEntity() instanceof ServerPlayer player
                && !canModifySpace(player, level)) {
            event.setCanceled(true);
            AccessControl.deny(player);
        }
    }

    @SubscribeEvent
    public static void onBlockToolModification(BlockEvent.BlockToolModificationEvent event) {
        if (event.isSimulated()) return;
        if (event.getLevel() instanceof Level level
                && event.getPlayer() instanceof ServerPlayer player
                && !canModifySpace(player, level)) {
            event.setCanceled(true);
            AccessControl.deny(player);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        SpaceData space = AccessControl.containingSpace(event.getLevel());
        if (space == null || space.can(player.getUUID(), SpacePermission.AccessLevel.USE)) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        AccessControl.deny(player);
    }

    /**
     * NeoForge 内置钩子：LevelEvent.PotentialSpawns
     * 在 NaturalSpawner.mobsAt() 中计算某位置的潜在可生成生物时触发。
     * 取消它 = 该位置不会尝试生成任何生物，比 FinalizeSpawnEvent 更早拦截。
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (!(event.getLevel() instanceof Level level)) return;
        if (!PocketDimensionManager.getInstance().isPocketDimension(level.dimension())) return;

        SpaceData space = PocketDimensionManager.findSpaceAt(level,
                event.getPos().getX(), event.getPos().getZ());

        if (space == null) {
            event.setCanceled(true);
            return;
        }

        if (!space.isMobSpawning()) {
            event.setCanceled(true);
            return;
        }

        if (!space.isInfinite() && level instanceof ServerLevel serverLevel
                && isPocketMobCapFull(serverLevel, space, event.getMobCategory(), false)) {
            event.setCanceled(true);
            return;
        }

        for (MobSpawnSettings.SpawnerData data : java.util.List.copyOf(event.getSpawnerDataList())) {
            ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(data.type);
            if (entityId != null && isPocketSpawnBlacklisted(entityId)) {
                event.removeSpawnerData(data);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMobPositionCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!PocketDimensionManager.getInstance().isPocketDimension(level.dimension())) return;

        int blockX = (int) Math.floor(event.getX());
        int blockZ = (int) Math.floor(event.getZ());
        SpaceData space = PocketDimensionManager.findSpaceAt(level, blockX, blockZ);
        if (space == null || !space.isMobSpawning()) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
            return;
        }
        if (!space.isInfinite() && isPocketMobCapFull(level, space, event.getEntity().getType().getCategory(), true)) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    private static boolean isPocketSpawnBlacklisted(ResourceLocation entityId) {
        if (entityId == null) return false;
        for (String value : ModConfig.POCKET_DIMENSION_ENTITY_SPAWN_BLACKLIST.get()) {
            ResourceLocation blocked = ResourceLocation.tryParse(value);
            if (entityId.equals(blocked)) return true;
        }
        return false;
    }

    private static boolean canModifySpace(ServerPlayer player, Level level) {
        SpaceData space = AccessControl.containingSpace(level);
        return space == null || space.can(player.getUUID(), SpacePermission.AccessLevel.WRITE);
    }

    private static boolean isPocketMobCapFull(ServerLevel level, SpaceData space, MobCategory category, boolean reserveSlot) {
        int cap = pocketMobCap(level, space, category);
        if (cap <= 0) return true;
        SpawnCapCounter counter = pocketSpawnCounter(level, space, category);
        if (counter.count >= cap) return true;
        if (reserveSlot) counter.count++;
        return false;
    }

    private static SpawnCapCounter pocketSpawnCounter(ServerLevel level, SpaceData space, MobCategory category) {
        long tick = level.getGameTime();
        if (pocketSpawnCapCacheTick != tick) {
            pocketSpawnCapCacheTick = tick;
            pocketSpawnCapCache.clear();
        }
        SpawnCapKey key = new SpawnCapKey(space.getSpaceId(), category);
        return pocketSpawnCapCache.computeIfAbsent(key, ignored ->
                new SpawnCapCounter(countPocketMobs(level, space, category)));
    }

    private static int pocketMobCap(ServerLevel level, SpaceData space, MobCategory category) {
        int vanillaCap = category.getMaxInstancesPerChunk();
        if (vanillaCap <= 0) return 0;
        int chunks = pocketEligibleChunkCount(level, space);
        if (chunks <= 0) return 0;
        return Math.max(1, (int) Math.ceil(vanillaCap * chunks / (double) VANILLA_MOB_CAP_CHUNKS));
    }

    private static int pocketEligibleChunkCount(ServerLevel level, SpaceData space) {
        int halfW = space.getWidth() / 2;
        int halfD = space.getDepth() / 2;
        int spaceMinChunkX = Math.floorDiv(-halfW, 16);
        int spaceMaxChunkX = Math.floorDiv(halfW - 1, 16);
        int spaceMinChunkZ = Math.floorDiv(-halfD, 16);
        int spaceMaxChunkZ = Math.floorDiv(halfD - 1, 16);
        Set<Long> chunks = new HashSet<>();

        for (ServerPlayer player : level.players()) {
            ChunkPos center = player.chunkPosition();
            int minChunkX = Math.max(spaceMinChunkX, center.x - 8);
            int maxChunkX = Math.min(spaceMaxChunkX, center.x + 8);
            int minChunkZ = Math.max(spaceMinChunkZ, center.z - 8);
            int maxChunkZ = Math.min(spaceMaxChunkZ, center.z + 8);
            if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) continue;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    chunks.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }
        return chunks.size();
    }

    private static int countPocketMobs(ServerLevel level, SpaceData space, MobCategory category) {
        int halfW = space.getWidth() / 2;
        int halfD = space.getDepth() / 2;
        AABB box = new AABB(-halfW, level.getMinBuildHeight(), -halfD,
                halfW, level.getMaxBuildHeight(), halfD);
        return level.getEntitiesOfClass(Mob.class, box, mob ->
                mob.isAlive()
                        && !mob.isPersistenceRequired()
                        && mob.getType().getCategory() == category
                        && isInsideSpace(space, mob.getBlockX(), mob.getBlockZ())
        ).size();
    }

    private static boolean isInsideSpace(SpaceData space, int x, int z) {
        int halfW = space.getWidth() / 2;
        int halfD = space.getDepth() / 2;
        return x >= -halfW && x < halfW && z >= -halfD && z < halfD;
    }

    private static void clearPocketSpawnCapCache() {
        pocketSpawnCapCache.clear();
        pocketSpawnCapCacheTick = Long.MIN_VALUE;
    }

    private static void ejectIfUnauthorizedPocketResident(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        Level level = serverPlayer.level();
        if (!PocketDimensionManager.getInstance().isPocketDimension(level.dimension())) return;

        SpaceData space = SpaceManager.getInstance().getSpaceByDimension(level.dimension().location());
        if (space != null && space.canAccess(serverPlayer.getUUID())) return;

        denyPocketEntry(serverPlayer);
        PocketDimensionManager.getInstance().exitToReturnPosition(serverPlayer);
    }

    private static void denyPocketEntry(ServerPlayer player) {
        player.displayClientMessage(Component.literal("你没有权限进入该口袋空间"), true);
    }

    private record SpawnCapKey(UUID spaceId, MobCategory category) {}
    private static final class SpawnCapCounter {
        private int count;

        private SpawnCapCounter(int count) {
            this.count = count;
        }
    }
}
