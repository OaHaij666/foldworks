package com.pockethomestead.scheduler;

import com.pockethomestead.config.ModConfig;

// 性能预算管理 - 根据配置分配计算资源
public class PerformanceBudget {
    private int maxConcurrentSpaces;
    private int ticksPerSession;
    private long verificationIntervalMs;

    public PerformanceBudget() {
        reload();
    }

    // 从配置重新加载
    public void reload() {
        this.maxConcurrentSpaces = ModConfig.MAX_CONCURRENT_SPACES.get();
        this.ticksPerSession = ModConfig.TICKS_PER_SESSION.get();
        this.verificationIntervalMs = ModConfig.VERIFICATION_INTERVAL_SECONDS.get() * 1000L;
    }

    public int getMaxConcurrentSpaces() { return maxConcurrentSpaces; }
    public int getTicksPerSession() { return ticksPerSession; }
    public long getVerificationIntervalMs() { return verificationIntervalMs; }
}
