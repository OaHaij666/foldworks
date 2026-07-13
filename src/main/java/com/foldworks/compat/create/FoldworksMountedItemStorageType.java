package com.foldworks.compat.create;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class FoldworksMountedItemStorageType extends MountedItemStorageType<FoldworksMountedItemStorage> {
    public FoldworksMountedItemStorageType() {
        super(FoldworksMountedItemStorage.CODEC);
    }

    @Override
    public @Nullable FoldworksMountedItemStorage mount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
        BaseChestBlockEntity chest = FoldworksChestAccess.resolve(be);
        return chest == null ? null : new FoldworksMountedItemStorage(this, chest);
    }
}
