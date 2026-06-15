package com.pockethomestead.scheduler;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayDeque;
import java.util.Queue;

// 速率计算器 - 使用滑动窗口算法计算物品产出速率
public class RateCalculator {
    // 滑动窗口大小（记录最近N次输出事件）
    private static final int WINDOW_SIZE = 100;
    // 事件记录：{游戏刻, 数量}
    private final Queue<long[]> events = new ArrayDeque<>();
    // 总数量（窗口内）
    private long totalAmount = 0;
    // 上次计算的速率（items/tick），服务器重启后可直接恢复
    private float lastKnownRate = 0.0f;

    // 记录一次输出
    public void record(int amount, long gameTick) {
        events.add(new long[]{gameTick, amount});
        totalAmount += amount;

        while (events.size() > WINDOW_SIZE) {
            long[] removed = events.poll();
            totalAmount -= removed[1];
        }
    }

    // 计算当前速率（items/tick）
    public float getRatePerTick(long currentTick) {
        if (events.isEmpty()) return lastKnownRate;
        if (currentTick <= 0) return 0.0f;

        long oldestTick = events.peek()[0];
        long tickSpan = currentTick - oldestTick;
        if (tickSpan <= 0) return 0.0f;

        lastKnownRate = (float) totalAmount / tickSpan;
        return lastKnownRate;
    }

    // 计算当前速率（items/second，20tick=1秒）
    public float getRatePerSecond(long currentTick) {
        return getRatePerTick(currentTick) * 20.0f;
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putLong("TotalAmount", totalAmount);
        tag.putFloat("LastRate", lastKnownRate);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.totalAmount = tag.getLong("TotalAmount");
        this.lastKnownRate = tag.getFloat("LastRate");
        this.events.clear();
    }

    public void clear() {
        events.clear();
        totalAmount = 0;
        lastKnownRate = 0.0f;
    }
}
