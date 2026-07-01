package com.pockethomestead.client;

import com.pockethomestead.network.SpaceInfo;

import java.util.List;

public final class ClientSpaceCache {
    private static volatile List<SpaceInfo> cache = List.of();
    private static volatile long version = 0;

    private ClientSpaceCache() {}

    public static void update(List<SpaceInfo> spaces) {
        cache = List.copyOf(spaces);
        version++;
    }

    public static List<SpaceInfo> get() {
        return cache;
    }

    public static long version() {
        return version;
    }
}
