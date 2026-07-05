package com.pockethomestead.compat.create;

import com.pockethomestead.moving.MovingChestRuntime;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

public class HomesteadChestMovementBehaviour implements MovementBehaviour {
    private static final String DATA_CHEST_KEY = "MovingChestData";

    @Override
    public void startMoving(MovementContext context) {
        runtime(context);
    }

    @Override
    public void tick(MovementContext context) {
        MovingChestRuntime runtime = runtime(context);
        if (runtime != null && context.world instanceof ServerLevel serverLevel) {
            syncMountedItemsIntoRuntime(context, runtime);
            syncMountedFluidsIntoRuntime(context, runtime);
            runtime.tick(serverLevel, context.blockEntityData);
            syncRuntimeItemsIntoMounted(context, runtime);
            syncRuntimeFluidsIntoMounted(context, runtime);
            rememberChestData(context);
        }
    }

    @Override
    public void stopMoving(MovementContext context) {
        MovingChestRuntime runtime = runtime(context);
        if (runtime == null) return;
        if (context.world instanceof ServerLevel serverLevel) {
            syncMountedItemsIntoRuntime(context, runtime);
            syncMountedFluidsIntoRuntime(context, runtime);
            runtime.writeTo(context.blockEntityData, serverLevel.registryAccess());
            syncRuntimeItemsIntoMounted(context, runtime);
            syncRuntimeFluidsIntoMounted(context, runtime);
            rememberChestData(context);
        }
        runtime.close();
        context.temporaryData = null;
    }

    @Override
    public void writeExtraData(MovementContext context) {
        MovingChestRuntime runtime = runtime(context);
        if (runtime != null && context.world instanceof ServerLevel serverLevel) {
            syncMountedItemsIntoRuntime(context, runtime);
            syncMountedFluidsIntoRuntime(context, runtime);
            runtime.writeTo(context.blockEntityData, serverLevel.registryAccess());
            syncRuntimeItemsIntoMounted(context, runtime);
            syncRuntimeFluidsIntoMounted(context, runtime);
            rememberChestData(context);
        }
    }

    private MovingChestRuntime runtime(MovementContext context) {
        if (context == null || context.world == null || context.world.isClientSide) return null;
        if (context.temporaryData instanceof MovingChestRuntime runtime && !runtime.isClosed()) return runtime;
        if (!(context.world instanceof ServerLevel serverLevel) || context.blockEntityData == null) return null;
        restoreRememberedChestData(context);
        MovingChestRuntime runtime = MovingChestRuntime.create(serverLevel, context.state, context.blockEntityData);
        context.temporaryData = runtime;
        return runtime;
    }

    private void rememberChestData(MovementContext context) {
        if (context == null || context.blockEntityData == null || context.data == null) return;
        if (context.blockEntityData.contains(MovingChestRuntime.CHEST_DATA_KEY, Tag.TAG_COMPOUND)) {
            context.data.put(DATA_CHEST_KEY, context.blockEntityData.getCompound(MovingChestRuntime.CHEST_DATA_KEY).copy());
        }
    }

    private void restoreRememberedChestData(MovementContext context) {
        if (context == null || context.blockEntityData == null || context.data == null) return;
        if (context.data.contains(DATA_CHEST_KEY, Tag.TAG_COMPOUND)) {
            context.blockEntityData.put(MovingChestRuntime.CHEST_DATA_KEY, context.data.getCompound(DATA_CHEST_KEY).copy());
        }
    }

    private void syncMountedItemsIntoRuntime(MovementContext context, MovingChestRuntime runtime) {
        if (context.getItemStorage() instanceof HomesteadMountedItemStorage storage && storage.consumeDirty()) {
            runtime.chest().replaceStorageFromOfflineSnapshot(
                    storage.copyItems(),
                    runtime.chest().getAllFluids(),
                    runtime.chest().getEnergyStored()
            );
        }
    }

    private void syncRuntimeItemsIntoMounted(MovementContext context, MovingChestRuntime runtime) {
        if (context.getItemStorage() instanceof HomesteadMountedItemStorage storage) {
            storage.replaceItems(runtime.chest().getStoredItems());
        }
    }

    private void syncMountedFluidsIntoRuntime(MovementContext context, MovingChestRuntime runtime) {
        if (context.getFluidStorage() instanceof HomesteadMountedFluidStorage storage && storage.consumeDirty()) {
            runtime.chest().replaceStorageFromOfflineSnapshot(
                    runtime.chest().getStoredItems(),
                    storage.copyFluids(),
                    runtime.chest().getEnergyStored()
            );
        }
    }

    private void syncRuntimeFluidsIntoMounted(MovementContext context, MovingChestRuntime runtime) {
        if (context.getFluidStorage() instanceof HomesteadMountedFluidStorage storage) {
            storage.replaceFluids(runtime.chest().getAllFluids());
        }
    }
}
