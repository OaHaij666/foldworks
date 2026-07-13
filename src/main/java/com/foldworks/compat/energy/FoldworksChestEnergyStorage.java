package com.foldworks.compat.energy;

import com.foldworks.blockentity.BaseChestBlockEntity;
import net.neoforged.neoforge.energy.IEnergyStorage;

public class FoldworksChestEnergyStorage implements IEnergyStorage {
    private final BaseChestBlockEntity chest;
    private final boolean canReceive;
    private final boolean canExtract;

    public FoldworksChestEnergyStorage(BaseChestBlockEntity chest) {
        this(chest, true, true);
    }

    public FoldworksChestEnergyStorage(BaseChestBlockEntity chest, boolean canReceive, boolean canExtract) {
        this.chest = chest;
        this.canReceive = canReceive;
        this.canExtract = canExtract;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (!canReceive) return 0;
        return chest.receiveEnergyInternal(maxReceive, simulate);
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (!canExtract) return 0;
        return chest.extractEnergyInternal(maxExtract, simulate);
    }

    @Override
    public int getEnergyStored() {
        return chest.getEnergyStored();
    }

    @Override
    public int getMaxEnergyStored() {
        return chest.getMaxEnergyStored();
    }

    @Override
    public boolean canExtract() {
        return canExtract && chest.getEnergyStored() > 0;
    }

    @Override
    public boolean canReceive() {
        return canReceive && chest.getRemainingEnergyCapacity() > 0;
    }
}
