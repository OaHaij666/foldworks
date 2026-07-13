package com.foldworks.compat.create;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.foldworks.blockentity.StoredItemStack;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorageType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FoldworksMountedItemStorage extends MountedItemStorage {
    public static final MapCodec<FoldworksMountedItemStorage> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("capacity").forGetter(storage -> storage.maxItemCapacity),
            Entry.CODEC.listOf().fieldOf("items").forGetter(FoldworksMountedItemStorage::entriesForCodec)
    ).apply(instance, FoldworksMountedItemStorage::new));

    private final List<StoredItemStack> items = new ArrayList<>();
    private int maxItemCapacity;
    private boolean dirty;

    FoldworksMountedItemStorage(MountedItemStorageType<?> type, BaseChestBlockEntity chest) {
        super(type);
        this.maxItemCapacity = chest.getMaxItemCapacity();
        replaceItems(chest.getStoredItems());
    }

    private FoldworksMountedItemStorage(int maxItemCapacity, List<Entry> entries) {
        super(CreateMovementRegistries.itemStorageType());
        this.maxItemCapacity = Math.max(0, maxItemCapacity);
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null || entry.stack().isEmpty() || entry.count() <= 0) continue;
                addStoredItem(entry.stack(), entry.count());
            }
        }
    }

    public void replaceItems(List<StoredItemStack> source) {
        items.clear();
        if (source != null) {
            for (StoredItemStack entry : source) {
                if (entry != null && entry.count() > 0) addStoredItem(entry.prototype(), entry.count());
            }
        }
        sortItems();
        dirty = false;
    }

    public List<StoredItemStack> copyItems() {
        List<StoredItemStack> copy = new ArrayList<>();
        for (StoredItemStack entry : items) copy.add(new StoredItemStack(entry.prototype(), entry.count()));
        copy.sort(Comparator.comparing(StoredItemStack::sortKey));
        return copy;
    }

    public int maxItemCapacity() {
        return maxItemCapacity;
    }

    @Override
    public void unmount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
        BaseChestBlockEntity chest = FoldworksChestAccess.resolve(be);
        if (chest == null) return;
        chest.replaceStorageFromOfflineSnapshot(copyItems(), chest.getAllFluids(), chest.getEnergyStored());
    }

    @Override
    public void setStackInSlot(int slot, @NotNull ItemStack stack) {
        SlotRef ref = slotRef(slot);
        if (ref != null) {
            if (stack.isEmpty()) {
                items.remove(ref.index());
            } else if (ref.entry().matches(stack)) {
                ref.entry().shrink(ref.entry().count());
                ref.entry().grow(stack.getCount());
            } else {
                items.set(ref.index(), new StoredItemStack(stack, stack.getCount()));
            }
            sortItems();
            dirty = true;
            return;
        }
        if (!stack.isEmpty() && slot == storedSlotCount() && usedCapacity() < maxItemCapacity) {
            addStoredItem(stack, Math.min(stack.getCount(), maxItemCapacity - usedCapacity()));
            sortItems();
            dirty = true;
        }
    }

    @Override
    public int getSlots() {
        return Math.max(1, storedSlotCount() + (usedCapacity() < maxItemCapacity ? 1 : 0));
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        SlotRef ref = slotRef(slot);
        return ref == null ? ItemStack.EMPTY : ref.stack();
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        if (slot < 0 || slot >= getSlots() || stack.isEmpty()) return stack;
        int accepted = Math.min(stack.getCount(), Math.max(0, maxItemCapacity - usedCapacity()));
        if (accepted <= 0) return stack;
        if (!simulate) {
            addStoredItem(stack, accepted);
            sortItems();
            dirty = true;
        }
        ItemStack remainder = stack.copy();
        remainder.shrink(accepted);
        return remainder;
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        SlotRef ref = slotRef(slot);
        if (ref == null) return ItemStack.EMPTY;
        int extracted = Math.min(amount, ref.stack().getCount());
        if (extracted <= 0) return ItemStack.EMPTY;
        ItemStack result = ref.entry().prototype().copyWithCount(extracted);
        if (!simulate) {
            ref.entry().shrink(extracted);
            if (ref.entry().isEmpty()) items.remove(ref.index());
            dirty = true;
        }
        return result;
    }

    @Override
    public int getSlotLimit(int slot) {
        SlotRef ref = slotRef(slot);
        if (ref != null) return ref.entry().prototype().getMaxStackSize();
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return slot >= 0 && slot < getSlots() && !stack.isEmpty() && usedCapacity() < maxItemCapacity;
    }

    private int usedCapacity() {
        int total = 0;
        for (StoredItemStack entry : items) total += entry.count();
        return total;
    }

    private int storedSlotCount() {
        int slots = 0;
        for (StoredItemStack entry : items) {
            int max = Math.max(1, entry.prototype().getMaxStackSize());
            slots += (entry.count() + max - 1) / max;
        }
        return slots;
    }

    @Nullable
    private SlotRef slotRef(int slot) {
        if (slot < 0) return null;
        int cursor = 0;
        for (int i = 0; i < items.size(); i++) {
            StoredItemStack entry = items.get(i);
            int max = Math.max(1, entry.prototype().getMaxStackSize());
            int slots = (entry.count() + max - 1) / max;
            if (slot < cursor + slots) {
                int offset = slot - cursor;
                int count = Math.min(max, entry.count() - offset * max);
                return new SlotRef(i, entry, entry.prototype().copyWithCount(count));
            }
            cursor += slots;
        }
        return null;
    }

    private void addStoredItem(ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty() || amount <= 0) return;
        for (StoredItemStack entry : items) {
            if (entry.matches(stack)) {
                entry.grow(amount);
                return;
            }
        }
        items.add(new StoredItemStack(stack, amount));
    }

    private void sortItems() {
        items.removeIf(StoredItemStack::isEmpty);
        items.sort(Comparator.comparing(StoredItemStack::sortKey));
    }

    private List<Entry> entriesForCodec() {
        List<Entry> entries = new ArrayList<>();
        for (StoredItemStack entry : items) entries.add(new Entry(entry.prototype(), entry.count()));
        return entries;
    }

    public boolean consumeDirty() {
        boolean result = dirty;
        dirty = false;
        return result;
    }

    private record Entry(ItemStack stack, int count) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ItemStack.CODEC.fieldOf("stack").forGetter(Entry::stack),
                Codec.INT.fieldOf("count").forGetter(Entry::count)
        ).apply(instance, Entry::new));
    }

    private record SlotRef(int index, StoredItemStack entry, ItemStack stack) {}
}
