package com.foldworks.blockentity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class StoredItemStack {
    private final ItemStack prototype;
    private int count;
    private transient int nativeItemId = -1;

    public StoredItemStack(ItemStack prototype, int count) {
        if (prototype == null || prototype.isEmpty()) {
            throw new IllegalArgumentException("prototype must not be empty");
        }
        this.prototype = prototype.copyWithCount(1);
        this.count = Math.max(0, count);
    }

    public ItemStack prototype() {
        return prototype.copyWithCount(1);
    }

    public Item item() {
        return prototype.getItem();
    }

    public int count() {
        return count;
    }

    public void grow(int amount) {
        if (amount > 0) count += amount;
    }

    public int shrink(int amount) {
        int removed = Math.min(Math.max(0, amount), count);
        count -= removed;
        return removed;
    }

    public boolean isEmpty() {
        return count <= 0;
    }

    public boolean matches(ItemStack stack) {
        return stack != null && !stack.isEmpty() && ItemStack.isSameItemSameComponents(prototype, stack);
    }

    public ItemStack stack(int amount) {
        return prototype.copyWithCount(Math.min(Math.max(0, amount), count));
    }

    public String itemId() {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item());
        return id == null ? "" : id.toString();
    }

    public String sortKey() {
        return itemId() + "|" + prototype.getComponentsPatch().hashCode() + "|" + prototype.getHoverName().getString();
    }

    public int nativeItemId() {
        return nativeItemId;
    }

    public void nativeItemId(int id) {
        this.nativeItemId = id;
    }

    public ItemStack prototypeRef() {
        return prototype;
    }
}
