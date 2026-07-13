package com.foldworks.compat.create;

import com.foldworks.Foldworks;
import com.foldworks.registration.ModBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import net.minecraft.world.level.block.Block;

public final class CreateMovementCompat {
    private static boolean registered;

    private CreateMovementCompat() {
    }

    public static void register() {
        if (registered) return;
        Block chestBlock = ModBlocks.FOLDWORKS_CHEST.get();
        BlockMovementChecks.registerMovementAllowedCheck((state, world, pos) ->
                state.is(chestBlock)
                        ? BlockMovementChecks.CheckResult.SUCCESS
                        : BlockMovementChecks.CheckResult.PASS);
        registerMountedStorage(chestBlock);
        MovementBehaviour.REGISTRY.register(chestBlock, new FoldworksChestMovementBehaviour());
        registered = true;
        Foldworks.LOGGER.info("Create movement compatibility registered for foldworks chest");
    }

    private static void registerMountedStorage(Block chestBlock) {
        try {
            MountedItemStorageType.REGISTRY.register(chestBlock, CreateMovementRegistries.itemStorageType());
            MountedFluidStorageType.REGISTRY.register(chestBlock, CreateMovementRegistries.fluidStorageType());
        } catch (RuntimeException ex) {
            Foldworks.LOGGER.warn("Create mounted storage compatibility failed; moving foldworks chests will still run transfer graphs", ex);
        }
    }
}
