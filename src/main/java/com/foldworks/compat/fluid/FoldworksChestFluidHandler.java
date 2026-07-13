package com.foldworks.compat.fluid;

import com.foldworks.blockentity.BaseChestBlockEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 将维度工造箱子的虚拟流体 Map 暴露为 NeoForge 标准 FluidHandler。
 * Create 的管道/泵等物流组件会通过 Capabilities.FluidHandler.BLOCK 访问这里。
 */
public class FoldworksChestFluidHandler implements IFluidHandler {
    private final BaseChestBlockEntity chest;
    private final boolean canFill;
    private final boolean canDrain;

    public FoldworksChestFluidHandler(BaseChestBlockEntity chest) {
        this(chest, true, true);
    }

    public FoldworksChestFluidHandler(BaseChestBlockEntity chest, boolean canFill, boolean canDrain) {
        this.chest = chest;
        this.canFill = canFill;
        this.canDrain = canDrain;
    }

    private List<Map.Entry<Fluid, Integer>> fluids() {
        List<Map.Entry<Fluid, Integer>> entries = new ArrayList<>(chest.getAllFluids().entrySet());
        entries.sort(Comparator.comparing(entry -> BuiltInRegistries.FLUID.getKey(entry.getKey()).toString()));
        return entries;
    }

    @Override
    public int getTanks() {
        return Math.max(1, chest.getMaxFluidTypes());
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        List<Map.Entry<Fluid, Integer>> entries = fluids();
        if (tank < 0 || tank >= entries.size()) return FluidStack.EMPTY;
        Map.Entry<Fluid, Integer> entry = entries.get(tank);
        if (entry.getKey() == Fluids.EMPTY || entry.getValue() <= 0) return FluidStack.EMPTY;
        return new FluidStack(entry.getKey(), entry.getValue());
    }

    @Override
    public int getTankCapacity(int tank) {
        return chest.getMaxFluidCapacityPerTypeMb();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return canFill && stack != null && !stack.isEmpty();
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!canFill) return 0;
        if (resource == null || resource.isEmpty()) return 0;
        int accepted = Math.min(resource.getAmount(), chest.getRemainingFluidCapacityMb(resource.getFluid()));
        if (accepted <= 0) return 0;
        if (action.execute()) {
            chest.addFluid(resource.getFluid(), accepted);
        }
        return accepted;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (!canDrain) return FluidStack.EMPTY;
        if (resource == null || resource.isEmpty()) return FluidStack.EMPTY;
        int available = chest.getAllFluids().getOrDefault(resource.getFluid(), 0);
        int drained = Math.min(resource.getAmount(), available);
        if (drained <= 0) return FluidStack.EMPTY;
        if (action.execute()) {
            chest.removeFluid(resource.getFluid(), drained);
        }
        return new FluidStack(resource.getFluid(), drained);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (!canDrain) return FluidStack.EMPTY;
        if (maxDrain <= 0) return FluidStack.EMPTY;
        for (Map.Entry<Fluid, Integer> entry : fluids()) {
            if (entry.getKey() == Fluids.EMPTY || entry.getValue() <= 0) continue;
            int drained = Math.min(maxDrain, entry.getValue());
            if (action.execute()) {
                chest.removeFluid(entry.getKey(), drained);
            }
            return new FluidStack(entry.getKey(), drained);
        }
        return FluidStack.EMPTY;
    }
}
