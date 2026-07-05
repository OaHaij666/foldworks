package com.pockethomestead.compat.create;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class HomesteadMountedItemStorageType extends MountedItemStorageType<HomesteadMountedItemStorage> {
    public HomesteadMountedItemStorageType() {
        super(HomesteadMountedItemStorage.CODEC);
    }

    @Override
    public @Nullable HomesteadMountedItemStorage mount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
        BaseChestBlockEntity chest = HomesteadChestAccess.resolve(be);
        return chest == null ? null : new HomesteadMountedItemStorage(this, chest);
    }
}
