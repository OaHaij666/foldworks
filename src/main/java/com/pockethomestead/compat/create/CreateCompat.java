package com.pockethomestead.compat.create;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.block.HomesteadChestBlock;
import com.pockethomestead.blockentity.HomesteadChestBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

public final class CreateCompat {
    private static final String CREATE_CHEST_BLOCK = "com.pockethomestead.compat.create.CreateHomesteadChestBlock";
    private static final String CREATE_CHEST_BE = "com.pockethomestead.compat.create.CreateHomesteadChestBlockEntity";

    private CreateCompat() {
    }

    public static boolean isCreateLoaded() {
        return ModList.get().isLoaded("create");
    }

    public static Block createHomesteadChestBlock(BlockBehaviour.Properties properties) {
        if (isCreateLoaded()) {
            try {
                Class<?> type = Class.forName(CREATE_CHEST_BLOCK);
                return (Block) type.getConstructor(BlockBehaviour.Properties.class).newInstance(properties);
            } catch (ReflectiveOperationException | LinkageError ex) {
                PocketHomestead.LOGGER.warn("Create chest block compatibility failed; using plain homestead chest", ex);
            }
        }
        return new HomesteadChestBlock(properties);
    }

    public static BlockEntity createHomesteadChestBlockEntity(BlockPos pos, BlockState state) {
        if (isCreateLoaded()) {
            try {
                Class<?> type = Class.forName(CREATE_CHEST_BE);
                return (BlockEntity) type.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            } catch (ReflectiveOperationException | LinkageError ex) {
                PocketHomestead.LOGGER.warn("Create chest block entity compatibility failed; using plain homestead chest", ex);
            }
        }
        return new HomesteadChestBlockEntity(pos, state);
    }
}
