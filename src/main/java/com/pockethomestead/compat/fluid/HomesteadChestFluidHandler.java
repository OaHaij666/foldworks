package com.pockethomestead.compat.fluid;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.config.ModConfig;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将口袋家园箱子的虚拟流体 Map 暴露为 NeoForge 标准 FluidHandler。
 * Create 的管道/泵等物流组件会通过 Capabilities.FluidHandler.BLOCK 访问这里。
 */
public class HomesteadChestFluidHandler implements IFluidHandler {
    private final BaseChestBlockEntity chest;

    public HomesteadChestFluidHandler(BaseChestBlockEntity chest) {
        this.chest = chest;
    }

    private List<Map.Entry<Fluid, Integer>> fluids() {
        return new ArrayList<>(chest.getAllFluids().entrySet());
    }

    @Override
    public int getTanks() {
        return Math.max(1, chest.getAllFluids().size());
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
        return ModConfig.MAX_CHEST_FLUID_CAPACITY_MB.get();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return stack != null && !stack.isEmpty();
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) return 0;
        int accepted = Math.min(resource.getAmount(), chest.getRemainingFluidCapacityMb());
        if (accepted <= 0) return 0;
        if (action.execute()) {
            chest.addFluid(resource.getFluid(), accepted);
        }
        return accepted;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
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
