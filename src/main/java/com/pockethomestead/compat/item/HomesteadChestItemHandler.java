package com.pockethomestead.compat.item;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.StoredItemStack;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 将箱子的虚拟物品库存暴露为 NeoForge 标准 ItemHandler，供 Create 等自动化组件访问。
 */
public class HomesteadChestItemHandler implements IItemHandler {
    private final BaseChestBlockEntity chest;
    private final boolean canInsert;
    private final boolean canExtract;

    public HomesteadChestItemHandler(BaseChestBlockEntity chest) {
        this(chest, true, true);
    }

    public HomesteadChestItemHandler(BaseChestBlockEntity chest, boolean canInsert, boolean canExtract) {
        this.chest = chest;
        this.canInsert = canInsert;
        this.canExtract = canExtract;
    }

    private List<StoredItemStack> items() {
        return new ArrayList<>(chest.getStoredItems());
    }

    @Override
    public int getSlots() {
        int storedSlots = chest.getStoredItems().size();
        return Math.max(1, storedSlots + (chest.getRemainingCapacity() > 0 ? 1 : 0));
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        List<StoredItemStack> entries = items();
        if (slot < 0 || slot >= entries.size()) return ItemStack.EMPTY;
        StoredItemStack entry = entries.get(slot);
        if (entry.count() <= 0) return ItemStack.EMPTY;
        return entry.stack(Math.min(entry.count(), entry.item().getDefaultMaxStackSize()));
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!canInsert) return stack;
        if (slot < 0 || slot >= getSlots() || stack.isEmpty()) return stack;
        int accepted = Math.min(stack.getCount(), chest.getRemainingCapacity());
        if (accepted <= 0) return stack;
        if (!simulate) {
            accepted = chest.addItem(stack, accepted);
        }
        if (accepted <= 0) return stack;
        ItemStack remainder = stack.copy();
        remainder.shrink(accepted);
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!canExtract) return ItemStack.EMPTY;
        if (amount <= 0) return ItemStack.EMPTY;
        List<StoredItemStack> entries = items();
        if (slot < 0 || slot >= entries.size()) return ItemStack.EMPTY;
        StoredItemStack entry = entries.get(slot);
        int extracted = Math.min(amount, entry.count());
        if (extracted <= 0) return ItemStack.EMPTY;
        if (!simulate) {
            extracted = chest.removeItem(entry.prototype(), extracted);
        }
        return extracted <= 0 ? ItemStack.EMPTY : entry.prototype().copyWithCount(extracted);
    }

    @Override
    public int getSlotLimit(int slot) {
        return Math.max(1, chest.getRemainingCapacity() + getStackInSlot(slot).getCount());
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return canInsert && slot >= 0 && slot < getSlots() && !stack.isEmpty();
    }
}
