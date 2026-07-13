package com.foldworks.moving;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.offline.OfflineChestSnapshotStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class MovingChestRuntime implements AutoCloseable {
    public static final String CHEST_DATA_KEY = "ChestData";

    private final MovingFoldworksChestBlockEntity chest;
    private final String dimensionKey;
    private final BlockPos graphPos;
    private final BlockState state;
    private boolean closed;
    private long lastSnapshotGameTime = Long.MIN_VALUE;

    private MovingChestRuntime(MovingFoldworksChestBlockEntity chest, String dimensionKey, BlockPos graphPos, BlockState state) {
        this.chest = chest;
        this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
        this.graphPos = graphPos.immutable();
        this.state = state;
    }

    public static MovingChestRuntime create(ServerLevel level, BlockState state, CompoundTag blockEntityData) {
        if (level == null || state == null || blockEntityData == null) return null;
        CompoundTag chestTag = chestData(blockEntityData);
        if (chestTag == null) return null;

        String dimensionKey = chestTag.contains("LastKnownDimension")
                ? chestTag.getString("LastKnownDimension")
                : level.dimension().location().toString();
        BlockPos graphPos = chestTag.contains("LastKnownPos")
                ? BlockPos.of(chestTag.getLong("LastKnownPos"))
                : BlockPos.ZERO;

        MovingFoldworksChestBlockEntity chest = new MovingFoldworksChestBlockEntity(graphPos, state);
        chest.setLevel(level);
        chest.setBlockState(state);
        chest.loadCustomOnly(chestTag, level.registryAccess());
        MovingChestRuntime runtime = new MovingChestRuntime(chest, dimensionKey, graphPos, state);
        MovingChestRegistry.register(runtime);
        runtime.captureMovingSnapshot(level, level.getGameTime());
        return runtime;
    }

    public void tick(ServerLevel level, CompoundTag blockEntityData) {
        if (closed || level == null) return;
        chest.setLevel(level);
        chest.setBlockState(state);
        MovingChestRegistry.register(this);
        BaseChestBlockEntity.serverTick(level, graphPos, state, chest);
        long gameTime = level.getGameTime();
        if (lastSnapshotGameTime == Long.MIN_VALUE || gameTime - lastSnapshotGameTime >= 20) {
            captureMovingSnapshot(level, gameTime);
        }
        writeTo(blockEntityData, level.registryAccess());
    }

    public void writeTo(CompoundTag blockEntityData, HolderLookup.Provider registries) {
        if (blockEntityData == null || registries == null) return;
        blockEntityData.put(CHEST_DATA_KEY, chest.saveCustomOnly(registries));
    }

    private void captureMovingSnapshot(ServerLevel level, long gameTime) {
        OfflineChestSnapshotStorage.get(level.getServer()).captureMoving(chest, gameTime);
        lastSnapshotGameTime = gameTime;
    }

    public BaseChestBlockEntity chest() {
        return chest;
    }

    public String dimensionKey() {
        return dimensionKey;
    }

    public BlockPos graphPos() {
        return graphPos;
    }

    public String chestId() {
        return chest.getChestId();
    }

    public boolean isValid() {
        return !dimensionKey.isBlank() && graphPos != null && !chest.getChestId().isBlank();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        MovingChestRegistry.unregister(this);
    }

    private static CompoundTag chestData(CompoundTag blockEntityData) {
        if (blockEntityData.contains(CHEST_DATA_KEY, Tag.TAG_COMPOUND)) {
            return blockEntityData.getCompound(CHEST_DATA_KEY);
        }
        if (blockEntityData.contains("ChestId")) {
            return blockEntityData;
        }
        return null;
    }
}
