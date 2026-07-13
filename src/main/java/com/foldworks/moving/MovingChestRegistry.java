package com.foldworks.moving;

import com.foldworks.blockentity.BaseChestBlockEntity;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

public final class MovingChestRegistry {
    private static final Map<Key, MovingChestRuntime> BY_LOCATION = new HashMap<>();

    private MovingChestRegistry() {
    }

    public static void register(MovingChestRuntime runtime) {
        if (runtime == null || !runtime.isValid()) return;
        BY_LOCATION.put(key(runtime.dimensionKey(), runtime.graphPos(), runtime.chestId()), runtime);
    }

    public static void unregister(MovingChestRuntime runtime) {
        if (runtime == null) return;
        BY_LOCATION.remove(key(runtime.dimensionKey(), runtime.graphPos(), runtime.chestId()), runtime);
    }

    public static BaseChestBlockEntity findChest(String dimensionKey, BlockPos pos, String chestId) {
        MovingChestRuntime runtime = BY_LOCATION.get(key(dimensionKey, pos, chestId));
        return runtime == null || runtime.isClosed() ? null : runtime.chest();
    }

    public static boolean isMoving(String dimensionKey, BlockPos pos, String chestId) {
        MovingChestRuntime runtime = BY_LOCATION.get(key(dimensionKey, pos, chestId));
        return runtime != null && !runtime.isClosed();
    }

    public static void clear() {
        BY_LOCATION.clear();
    }

    private static Key key(String dimensionKey, BlockPos pos, String chestId) {
        return new Key(dimensionKey == null ? "" : dimensionKey, pos == null ? 0L : pos.asLong(), chestId == null ? "" : chestId);
    }

    private record Key(String dimensionKey, long pos, String chestId) {}
}
