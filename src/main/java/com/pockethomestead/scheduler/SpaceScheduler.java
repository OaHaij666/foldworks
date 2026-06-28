package com.pockethomestead.scheduler;

import com.pockethomestead.config.ModConfig;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

public class SpaceScheduler {
    private static SpaceScheduler instance;
    private final PerformanceBudget budget = new PerformanceBudget();
    private final Set<UUID> schedulingQueue = new LinkedHashSet<>();
    private final Map<UUID, Long> lastVerificationTime = new HashMap<>();
    // spaceId -> remaining ticks in current session
    private final Map<UUID, Integer> sessionTicksRemaining = new HashMap<>();
    private final Set<UUID> currentlyTicking = new HashSet<>();
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

        rebuildQueue();
        checkVerification(server);
        processSchedulingQueue(server);
        tickActiveSessions(server);
    }

    private void rebuildQueue() {
        Collection<SpaceData> allSpaces = SpaceManager.getInstance().getAllSpaces();
        Set<UUID> existingIds = new HashSet<>(schedulingQueue);
        existingIds.addAll(currentlyTicking);

        for (SpaceData space : allSpaces) {
            if (!existingIds.contains(space.getSpaceId())) {
                schedulingQueue.add(space.getSpaceId());
            }
        }
    }

    private void checkVerification(MinecraftServer server) {
        long currentTime = System.currentTimeMillis();
        for (SpaceData space : SpaceManager.getInstance().getAllSpaces()) {
            UUID spaceId = space.getSpaceId();
            Long lastTime = lastVerificationTime.get(spaceId);
            if (lastTime == null || (currentTime - lastTime) > budget.getVerificationIntervalMs()) {
                if (!currentlyTicking.contains(spaceId) && !schedulingQueue.contains(spaceId)) {
                    schedulingQueue.add(spaceId);
                    lastVerificationTime.put(spaceId, currentTime);
                }
            }
        }
    }

    private void processSchedulingQueue(MinecraftServer server) {
        Iterator<UUID> it = schedulingQueue.iterator();
        while (currentlyTicking.size() < budget.getMaxConcurrentSpaces() && it.hasNext()) {
            UUID spaceId = it.next();
            it.remove();
            SpaceData space = SpaceManager.getInstance().getSpace(spaceId);
            if (space == null) continue;
            currentlyTicking.add(spaceId);
            sessionTicksRemaining.put(spaceId, budget.getTicksPerSession());
        }
    }

    // 每次调度循环（每20tick）推进所有活跃空间的会话倒计时，并执行物品传输
    private void tickActiveSessions(MinecraftServer server) {
        Iterator<UUID> it = currentlyTicking.iterator();
        while (it.hasNext()) {
            UUID spaceId = it.next();
            SpaceData space = SpaceManager.getInstance().getSpace(spaceId);
            if (space != null) {
                // 物品传输已改为箱子自驱动（BaseChestBlockEntity.serverTick），不再需要集中调度
            }
            int remaining = sessionTicksRemaining.getOrDefault(spaceId, 0) - 20;
            if (remaining <= 0) {
                it.remove();
                sessionTicksRemaining.remove(spaceId);
            } else {
                sessionTicksRemaining.put(spaceId, remaining);
            }
        }
    }

    public Set<UUID> getCurrentlyTicking() {
        return Collections.unmodifiableSet(currentlyTicking);
    }

    public PerformanceBudget getBudget() { return budget; }

    public void reset() {
        schedulingQueue.clear();
        lastVerificationTime.clear();
        sessionTicksRemaining.clear();
        currentlyTicking.clear();
        tickCounter = 0;
    }
}
