package com.foldworks.compat.create;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class FoldworksMountedFluidStorageType extends MountedFluidStorageType<FoldworksMountedFluidStorage> {
    public FoldworksMountedFluidStorageType() {
        super(FoldworksMountedFluidStorage.CODEC);
    }

    @Override
    public @Nullable FoldworksMountedFluidStorage mount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
        BaseChestBlockEntity chest = FoldworksChestAccess.resolve(be);
        return chest == null || chest.getMaxFluidTypes() <= 0 ? null : new FoldworksMountedFluidStorage(this, chest);
    }
}
