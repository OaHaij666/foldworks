package com.foldworks.compat.create;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FoldworksMountedFluidStorage extends MountedFluidStorage {
    public static final MapCodec<FoldworksMountedFluidStorage> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("max_types").forGetter(storage -> storage.maxFluidTypes),
            Codec.INT.fieldOf("capacity_per_type").forGetter(storage -> storage.maxFluidCapacityPerTypeMb),
            Entry.CODEC.listOf().fieldOf("fluids").forGetter(FoldworksMountedFluidStorage::entriesForCodec)
    ).apply(instance, FoldworksMountedFluidStorage::new));

    private final Map<Fluid, Integer> fluids = new LinkedHashMap<>();
    private int maxFluidTypes;
    private int maxFluidCapacityPerTypeMb;
    private boolean dirty;

    FoldworksMountedFluidStorage(MountedFluidStorageType<?> type, BaseChestBlockEntity chest) {
        super(type);
        this.maxFluidTypes = chest.getMaxFluidTypes();
        this.maxFluidCapacityPerTypeMb = chest.getMaxFluidCapacityPerTypeMb();
        replaceFluids(chest.getAllFluids());
    }

    private FoldworksMountedFluidStorage(int maxFluidTypes, int maxFluidCapacityPerTypeMb, List<Entry> entries) {
        super(CreateMovementRegistries.fluidStorageType());
        this.maxFluidTypes = Math.max(0, maxFluidTypes);
        this.maxFluidCapacityPerTypeMb = Math.max(0, maxFluidCapacityPerTypeMb);
        if (entries != null) {
            for (Entry entry : entries) {
                Fluid fluid = resolveFluid(entry.id());
                if (fluid != Fluids.EMPTY && entry.amount() > 0) {
                    fluids.put(fluid, Math.min(entry.amount(), this.maxFluidCapacityPerTypeMb));
                }
            }
        }
    }

    public void replaceFluids(Map<Fluid, Integer> source) {
        fluids.clear();
        if (source != null) {
            source.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> fluidId(entry.getKey())))
                    .forEach(entry -> {
                        if (entry.getKey() != null && entry.getKey() != Fluids.EMPTY && entry.getValue() > 0) {
                            fluids.put(entry.getKey(), Math.min(entry.getValue(), maxFluidCapacityPerTypeMb));
                        }
                    });
        }
        dirty = false;
    }

    public Map<Fluid, Integer> copyFluids() {
        return new LinkedHashMap<>(fluids);
    }

    public boolean consumeDirty() {
        boolean result = dirty;
        dirty = false;
        return result;
    }

    @Override
    public void unmount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
        BaseChestBlockEntity chest = FoldworksChestAccess.resolve(be);
        if (chest == null) return;
        chest.replaceStorageFromOfflineSnapshot(chest.getStoredItems(), copyFluids(), chest.getEnergyStored());
    }

    @Override
    public int getTanks() {
        return Math.max(1, maxFluidTypes);
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        List<Map.Entry<Fluid, Integer>> entries = fluidEntries();
        if (tank < 0 || tank >= entries.size()) return FluidStack.EMPTY;
        Map.Entry<Fluid, Integer> entry = entries.get(tank);
        return entry.getValue() <= 0 ? FluidStack.EMPTY : new FluidStack(entry.getKey(), entry.getValue());
    }

    @Override
    public int getTankCapacity(int tank) {
        return maxFluidCapacityPerTypeMb;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return stack != null && !stack.isEmpty();
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) return 0;
        int accepted = Math.min(resource.getAmount(), remainingCapacity(resource.getFluid()));
        if (accepted <= 0) return 0;
        if (action.execute()) {
            fluids.merge(resource.getFluid(), accepted, Integer::sum);
            dirty = true;
        }
        return accepted;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) return FluidStack.EMPTY;
        int current = fluids.getOrDefault(resource.getFluid(), 0);
        int drained = Math.min(resource.getAmount(), current);
        if (drained <= 0) return FluidStack.EMPTY;
        if (action.execute()) {
            setFluidAmount(resource.getFluid(), current - drained);
            dirty = true;
        }
        return new FluidStack(resource.getFluid(), drained);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        for (Map.Entry<Fluid, Integer> entry : fluidEntries()) {
            int drained = Math.min(maxDrain, entry.getValue());
            if (drained <= 0) continue;
            if (action.execute()) {
                setFluidAmount(entry.getKey(), entry.getValue() - drained);
                dirty = true;
            }
            return new FluidStack(entry.getKey(), drained);
        }
        return FluidStack.EMPTY;
    }

    private int remainingCapacity(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return 0;
        int current = fluids.getOrDefault(fluid, 0);
        if (current <= 0 && fluids.size() >= maxFluidTypes) return 0;
        return Math.max(0, maxFluidCapacityPerTypeMb - current);
    }

    private void setFluidAmount(Fluid fluid, int amount) {
        if (amount <= 0) fluids.remove(fluid);
        else fluids.put(fluid, amount);
    }

    private List<Map.Entry<Fluid, Integer>> fluidEntries() {
        List<Map.Entry<Fluid, Integer>> entries = new ArrayList<>(fluids.entrySet());
        entries.sort(Comparator.comparing(entry -> fluidId(entry.getKey())));
        return entries;
    }

    private List<Entry> entriesForCodec() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<Fluid, Integer> entry : fluidEntries()) {
            if (entry.getValue() > 0) entries.add(new Entry(fluidId(entry.getKey()), entry.getValue()));
        }
        return entries;
    }

    private static Fluid resolveFluid(String idValue) {
        ResourceLocation id = ResourceLocation.tryParse(idValue);
        if (id == null) return Fluids.EMPTY;
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    private static String fluidId(Fluid fluid) {
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
        return id == null ? "" : id.toString();
    }

    private record Entry(String id, int amount) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Entry::id),
                Codec.INT.fieldOf("amount").forGetter(Entry::amount)
        ).apply(instance, Entry::new));
    }
}
