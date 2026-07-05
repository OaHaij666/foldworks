package com.pockethomestead.compat.create;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class HomesteadMountedFluidStorageType extends MountedFluidStorageType<HomesteadMountedFluidStorage> {
    public HomesteadMountedFluidStorageType() {
        super(HomesteadMountedFluidStorage.CODEC);
    }

    @Override
    public @Nullable HomesteadMountedFluidStorage mount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
        BaseChestBlockEntity chest = HomesteadChestAccess.resolve(be);
        return chest == null || chest.getMaxFluidTypes() <= 0 ? null : new HomesteadMountedFluidStorage(this, chest);
    }
}
