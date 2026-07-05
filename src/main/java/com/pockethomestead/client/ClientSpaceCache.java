package com.pockethomestead.client;

import com.pockethomestead.network.SpaceInfo;

import java.util.List;

public final class ClientSpaceCache {
    private static volatile List<SpaceInfo> cache = List.of();
    private static volatile SpaceInfo ownerPermission;
    private static volatile long version = 0;

    private ClientSpaceCache() {}

    public static void update(List<SpaceInfo> spaces) {
        update(spaces, null);
    }

    public static void update(List<SpaceInfo> spaces, SpaceInfo ownerPermissionInfo) {
        cache = List.copyOf(spaces);
        ownerPermission = ownerPermissionInfo;
        version++;
    }

    public static List<SpaceInfo> get() {
        return cache;
    }

    public static long version() {
        return version;
    }

    public static SpaceInfo ownerPermission() {
        return ownerPermission;
    }
}
