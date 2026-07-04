package com.pockethomestead;

import com.pockethomestead.command.PocketHomesteadCommand;
import com.pockethomestead.archive.SpaceArchiveTransferManager;
import com.pockethomestead.dimension.PocketDimensionManager;
import com.pockethomestead.offline.OfflineChestSnapshotStorage;
import com.pockethomestead.permission.AccessControl;
import com.pockethomestead.scheduler.SpaceScheduler;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.space.SpaceStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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

@EventBusSubscriber(modid = PocketHomestead.MODID)
public class ModEvents {

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
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SpaceArchiveTransferManager.reset();
        SpaceScheduler.getInstance().reset();
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

        // 不在任何空间 → 允许（虚空区域不会有 spawn 尝试走到这里，安全起见放行）
        if (space == null) return;

        if (!space.isMobSpawning()) {
            event.setCanceled(true);
        }
    }

    private static boolean canModifySpace(ServerPlayer player, Level level) {
        SpaceData space = AccessControl.containingSpace(level);
        return space == null || space.can(player.getUUID(), SpacePermission.AccessLevel.WRITE);
    }
}
