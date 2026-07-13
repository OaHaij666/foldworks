package com.foldworks.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 客户端缓存：维度 id → 该维度可用群系列表（由服务端 DimensionBiomesPayload 写入）。 */
public final class ClientDimensionBiomeCache {
    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private ClientDimensionBiomeCache() {}

    public static void put(String dimensionId, List<String> biomes) {
        CACHE.put(dimensionId, List.copyOf(biomes));
    }

    /** 返回缓存的群系列表；未缓存返回 null（调用方据此显示“加载中”并发起请求）。 */
    public static List<String> get(String dimensionId) {
        return CACHE.get(dimensionId);
    }

    public static boolean has(String dimensionId) {
        return CACHE.containsKey(dimensionId);
    }

    public static void clear() {
        CACHE.clear();
    }
}
