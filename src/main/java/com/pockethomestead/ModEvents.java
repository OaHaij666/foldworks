package com.pockethomestead;

import com.pockethomestead.command.PocketHomesteadCommand;
import com.pockethomestead.dimension.PocketDimensionManager;
import com.pockethomestead.scheduler.SpaceScheduler;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceItemRegistry;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpaceStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = PocketHomestead.MODID)
public class ModEvents {

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
        SpaceScheduler.getInstance().reset();
        SpaceManager.getInstance().clearSpaces();
        PocketDimensionManager.getInstance().reset();
        SpaceItemRegistry.clearAll();
        SpaceStorage.clearInstance();
        PocketHomestead.LOGGER.info("口袋空间运行时缓存已清理");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        SpaceScheduler.getInstance().onServerTick(event);
        PocketDimensionManager.getInstance().onServerTick(event.getServer());
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
}
