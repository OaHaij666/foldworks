package com.foldworks.scheduler;

import com.foldworks.config.ModConfig;
import com.foldworks.offline.OfflineChestSnapshotStorage;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务器 tick 入口：节流到每 20 tick 后驱动离线快照模拟。
 *
 * 历史版本曾维护一套"调度会话"状态机（schedulingQueue/currentlyTicking/sessionTicksRemaining），
 * 但物品传输已改为箱子自驱动（BaseChestBlockEntity.serverTick），该状态机对空间零操作，
 * 已删除。当前仅保留 OfflineChestSnapshotStorage.tick 驱动与配置热更新支持。
 */
public class SpaceScheduler {
    private static SpaceScheduler instance;
    private final PerformanceBudget budget = new PerformanceBudget();
    private int tickCounter = 0;

    private SpaceScheduler() {}

    public static SpaceScheduler getInstance() {
        if (instance == null) instance = new SpaceScheduler();
        return instance;
    }

    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;

        tickCounter++;
        if (tickCounter % 20 != 0) return;

        OfflineChestSnapshotStorage.get(server).tick(server);
    }

    public PerformanceBudget getBudget() { return budget; }

    /** 配置热更新：由 ModConfigEvent.Reload 触发。 */
    public void reloadBudget() {
        budget.reload();
    }

    public void reset() {
        tickCounter = 0;
    }
}
