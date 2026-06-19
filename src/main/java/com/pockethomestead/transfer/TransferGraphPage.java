package com.pockethomestead.transfer;

import net.minecraft.nbt.CompoundTag;

public class TransferGraphPage {
    private final String id;
    private String name;
    private boolean enabled;
    private int order;

    public TransferGraphPage(String id, String name, boolean enabled, int order) {
        this.id = id;
        this.name = name == null || name.isBlank() ? "默认页" : name;
        this.enabled = enabled;
        this.order = order;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public int getOrder() { return order; }
    public void setName(String name) { if (name != null && !name.isBlank()) this.name = name; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setOrder(int order) { this.order = order; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Name", name);
        tag.putBoolean("Enabled", enabled);
        tag.putInt("Order", order);
        return tag;
    }

    public static TransferGraphPage load(CompoundTag tag) {
        return new TransferGraphPage(
                tag.getString("Id"),
                tag.getString("Name"),
                !tag.contains("Enabled") || tag.getBoolean("Enabled"),
                tag.getInt("Order")
        );
    }
}
