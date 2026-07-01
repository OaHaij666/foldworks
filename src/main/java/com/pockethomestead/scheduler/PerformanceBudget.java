package com.pockethomestead.scheduler;

import com.pockethomestead.config.ModConfig;

/**
 * 性能预算包装：从 ModConfig 读取调度参数。
 *TicksPerSession/VerificationInterval 历史上服务于已删除的调度状态机，
 * 配置项保留以兼容旧配置文件，但不再在运行时使用。
 */
public class PerformanceBudget {
    private int maxConcurrentSpaces;

    public PerformanceBudget() {
        reload();
    }

    public void reload() {
        this.maxConcurrentSpaces = ModConfig.MAX_CONCURRENT_SPACES.get();
    }

    public int getMaxConcurrentSpaces() { return maxConcurrentSpaces; }
}
