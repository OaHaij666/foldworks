package com.foldworks.compat.create;

import com.foldworks.Foldworks;
import com.foldworks.block.FoldworksChestBlock;
import com.foldworks.blockentity.FoldworksChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

public final class CreateCompat {
    private static final String CREATE_CHEST_BLOCK = "com.foldworks.compat.create.CreateFoldworksChestBlock";
    private static final String CREATE_CHEST_BE = "com.foldworks.compat.create.CreateFoldworksChestBlockEntity";
    private static final String CREATE_MOVEMENT_COMPAT = "com.foldworks.compat.create.CreateMovementCompat";
    private static final String CREATE_MOVEMENT_REGISTRIES = "com.foldworks.compat.create.CreateMovementRegistries";

    private CreateCompat() {
    }

    public static boolean isCreateLoaded() {
        return ModList.get().isLoaded("create");
    }

    public static Block createFoldworksChestBlock(BlockBehaviour.Properties properties) {
        if (isCreateLoaded()) {
            try {
                Class<?> type = Class.forName(CREATE_CHEST_BLOCK);
                return (Block) type.getConstructor(BlockBehaviour.Properties.class).newInstance(properties);
            } catch (ReflectiveOperationException | LinkageError ex) {
                Foldworks.LOGGER.warn("Create chest block compatibility failed; using plain foldworks chest", ex);
            }
        }
        return new FoldworksChestBlock(properties);
    }

    public static BlockEntity createFoldworksChestBlockEntity(BlockPos pos, BlockState state) {
        if (isCreateLoaded()) {
            try {
                Class<?> type = Class.forName(CREATE_CHEST_BE);
                return (BlockEntity) type.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            } catch (ReflectiveOperationException | LinkageError ex) {
                Foldworks.LOGGER.warn("Create chest block entity compatibility failed; using plain foldworks chest", ex);
            }
        }
        return new FoldworksChestBlockEntity(pos, state);
    }

    public static void registerEarlyMovementCompatibility(IEventBus modEventBus) {
        if (!isCreateLoaded()) return;
        try {
            Class.forName(CREATE_MOVEMENT_REGISTRIES).getMethod("register", IEventBus.class).invoke(null, modEventBus);
        } catch (ReflectiveOperationException | LinkageError ex) {
            Foldworks.LOGGER.warn("Create early movement compatibility failed; moving foldworks chest storage may not mount", ex);
        }
    }

    public static void registerMovementCompatibility() {
        if (!isCreateLoaded()) return;
        try {
            Class.forName(CREATE_MOVEMENT_COMPAT).getMethod("register").invoke(null);
        } catch (ReflectiveOperationException | LinkageError ex) {
            Foldworks.LOGGER.warn("Create movement compatibility failed; foldworks chests may stay immovable on contraptions", ex);
        }
    }
}
