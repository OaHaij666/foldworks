package com.pockethomestead.client;

import com.pockethomestead.network.SpaceInfo;

import java.util.List;

public final class ClientSpaceCache {
    private static volatile List<SpaceInfo> cache = List.of();

    private ClientSpaceCache() {}

    public static void update(List<SpaceInfo> spaces) {
        cache = List.copyOf(spaces);
    }

    public static List<SpaceInfo> get() {
        return cache;
    }
}
