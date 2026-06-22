package com.pockethomestead.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TransferEdge {
    public static final String PORT_ALL = "ALL";
    public static final String PORT_IN = "IN";
    public static final String ITEM_PREFIX = "ITEM:";
    public static final String FLUID_PREFIX = "FLUID:";
    public static final String FLUID_ALL = FLUID_PREFIX + "*";

    public record ItemRateSnapshot(String itemId, boolean rateLimitEnabled, int rateLimitSeconds, int rateLimitItems,
                                   String health, int actualRatePerMinute, boolean configured) {}

    private static final int STATS_WINDOW_TICKS = 20 * 20;

    private final String id;
    private String pageId;
    private final String fromNodeId;
    private final String toNodeId;
    private String fromPortKey;
    private String toPortKey;
    private boolean enabled;
    private final Map<String, ItemRate> itemRates = new LinkedHashMap<>();

    public TransferEdge(String id, String pageId, String fromNodeId, String toNodeId, String fromPortKey, String toPortKey,
                        boolean rateLimitEnabled, int rateLimitSeconds, int rateLimitItems, boolean enabled) {
        this.id = id;
        this.pageId = pageId;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.fromPortKey = fromPortKey == null || fromPortKey.isBlank() ? PORT_ALL : fromPortKey;
        this.toPortKey = toPortKey == null || toPortKey.isBlank() ? PORT_IN : toPortKey;
        this.enabled = enabled;
        if (rateLimitEnabled && (isItemPort() || isFluidPort())) {
            setItemRate(resourceId(), true, rateLimitSeconds, rateLimitItems);
        }
    }

    public String getId() { return id; }
    public String getPageId() { return pageId; }
    public String getFromNodeId() { return fromNodeId; }
    public String getToNodeId() { return toNodeId; }
    public String getFromPortKey() { return fromPortKey; }
    public String getToPortKey() { return toPortKey; }
    public boolean isEnabled() { return enabled; }
    public boolean isRateLimitEnabled() { return false; }
    public int getRateLimitSeconds() { return 1; }
    public int getRateLimitItems() { return 64; }
    public void setPageId(String pageId) { this.pageId = pageId; }
    public void setRateLimit(boolean enabled, int seconds, int items) {
        if (isItemPort() || isFluidPort()) setItemRate(resourceId(), enabled, seconds, items);
    }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void setItemRate(String itemId, boolean enabled, int seconds, int items) {
        if (!validItemId(itemId)) return;
        ItemRate row = itemRates.computeIfAbsent(itemId, ItemRate::new);
        row.configured = true;
        row.rateLimitEnabled = enabled;
        row.rateLimitSeconds = clampSeconds(seconds);
        row.rateLimitItems = clampRate(items);
        row.updateHealth();
    }

    public void removeItemRate(String itemId) {
        ItemRate row = itemRates.get(itemId);
        if (row == null) return;
        if (row.hasStats()) {
            row.configured = false;
            row.rateLimitEnabled = false;
            row.rateLimitSeconds = 1;
            row.rateLimitItems = 64;
            row.updateHealth();
        } else {
            itemRates.remove(itemId);
        }
    }

    public List<ItemRateSnapshot> getItemRates() {
        List<ItemRateSnapshot> rows = new ArrayList<>();
        for (ItemRate row : itemRates.values()) rows.add(row.snapshot());
        return rows;
    }

    public boolean canTransferAt(long gameTime) {
        return true;
    }

    public boolean canTransferAt(long gameTime, String itemId) {
        return remainingRateBudget(gameTime, itemId) > 0;
    }

    public int remainingRateBudget(long gameTime) {
        return Integer.MAX_VALUE;
    }

    public int remainingRateBudget(long gameTime, String itemId) {
        ItemRate row = itemRates.get(itemId);
        if (row == null && itemId != null && itemId.startsWith(FLUID_PREFIX)) row = itemRates.get(FLUID_ALL);
        if (row == null || !row.rateLimitEnabled) return Integer.MAX_VALUE;
        row.refreshRateWindow(gameTime);
        return Math.max(0, row.rateLimitItems - row.movedInRateWindow);
    }

    public void recordMoved(long gameTime, int amount) {
        recordMoved(gameTime, syntheticItemId(), amount);
    }

    public void recordMoved(long gameTime, String itemId, int amount) {
        if (!validItemId(itemId) || amount <= 0) return;
        ItemRate row = itemRates.computeIfAbsent(itemId, ItemRate::new);
        row.observed = true;
        if (row.rateLimitEnabled) {
            row.refreshRateWindow(gameTime);
            row.movedInRateWindow += amount;
        }
        row.refreshStatsWindow(gameTime);
        row.attemptedInStatsWindow += amount;
        row.movedInStatsWindow += amount;
        row.updateHealth();
        ItemRate wildcard = itemId != null && itemId.startsWith(FLUID_PREFIX) ? itemRates.get(FLUID_ALL) : null;
        if (wildcard != null && wildcard != row) {
            wildcard.observed = true;
            if (wildcard.rateLimitEnabled) {
                wildcard.refreshRateWindow(gameTime);
                wildcard.movedInRateWindow += amount;
            }
            wildcard.refreshStatsWindow(gameTime);
            wildcard.attemptedInStatsWindow += amount;
            wildcard.movedInStatsWindow += amount;
            wildcard.updateHealth();
        }
    }

    public void recordSourceBlocked(long gameTime) {
        recordSourceBlocked(gameTime, syntheticItemId());
    }

    public void recordSourceBlocked(long gameTime, String itemId) {
        if (!validItemId(itemId)) return;
        ItemRate row = itemRates.computeIfAbsent(itemId, ItemRate::new);
        row.observed = true;
        row.refreshStatsWindow(gameTime);
        row.attemptedInStatsWindow++;
        row.sourceBlockedInStatsWindow++;
        row.updateHealth();
        recordWildcardSourceBlocked(gameTime, itemId, row);
    }

    public void recordReceiverBlocked(long gameTime) {
        recordReceiverBlocked(gameTime, syntheticItemId());
    }

    public void recordReceiverBlocked(long gameTime, String itemId) {
        if (!validItemId(itemId)) return;
        ItemRate row = itemRates.computeIfAbsent(itemId, ItemRate::new);
        row.observed = true;
        row.refreshStatsWindow(gameTime);
        row.attemptedInStatsWindow++;
        row.receiverBlockedInStatsWindow++;
        row.updateHealth();
        recordWildcardReceiverBlocked(gameTime, itemId, row);
    }

    private void recordWildcardSourceBlocked(long gameTime, String itemId, ItemRate exact) {
        ItemRate wildcard = itemId != null && itemId.startsWith(FLUID_PREFIX) ? itemRates.get(FLUID_ALL) : null;
        if (wildcard == null || wildcard == exact) return;
        wildcard.observed = true;
        wildcard.refreshStatsWindow(gameTime);
        wildcard.attemptedInStatsWindow++;
        wildcard.sourceBlockedInStatsWindow++;
        wildcard.updateHealth();
    }

    private void recordWildcardReceiverBlocked(long gameTime, String itemId, ItemRate exact) {
        ItemRate wildcard = itemId != null && itemId.startsWith(FLUID_PREFIX) ? itemRates.get(FLUID_ALL) : null;
        if (wildcard == null || wildcard == exact) return;
        wildcard.observed = true;
        wildcard.refreshStatsWindow(gameTime);
        wildcard.attemptedInStatsWindow++;
        wildcard.receiverBlockedInStatsWindow++;
        wildcard.updateHealth();
    }

    public String getHealth() {
        if (!enabled) return "DISABLED";
        boolean healthy = false;
        boolean measured = false;
        boolean source = false;
        boolean receiver = false;
        for (ItemRate row : itemRates.values()) {
            String health = row.health();
            if ("DEADLOCKED".equals(health)) return health;
            if ("RECEIVER_BLOCKED".equals(health)) receiver = true;
            else if ("SOURCE_SHORTAGE".equals(health)) source = true;
            else if ("HEALTHY".equals(health)) healthy = true;
            if (!"UNMEASURED".equals(health)) measured = true;
        }
        if (receiver) return "RECEIVER_BLOCKED";
        if (source) return "SOURCE_SHORTAGE";
        if (healthy) return "HEALTHY";
        return measured ? "HEALTHY" : "UNMEASURED";
    }

    public int getActualRatePerMinute() {
        int total = 0;
        for (ItemRate row : itemRates.values()) total += row.actualRatePerMinute();
        return total;
    }

    public boolean isAllPort() { return PORT_ALL.equals(fromPortKey); }
    public boolean isItemPort() { return fromPortKey.startsWith(ITEM_PREFIX); }
    public boolean isFluidPort() { return fromPortKey.startsWith(FLUID_PREFIX); }
    public String itemId() { return isItemPort() ? fromPortKey.substring(ITEM_PREFIX.length()) : ""; }
    public String fluidId() { return isFluidPort() ? fromPortKey.substring(FLUID_PREFIX.length()) : ""; }
    public String resourceId() { return isItemPort() || isFluidPort() ? fromPortKey : ""; }

    public static String itemPort(String itemId) { return ITEM_PREFIX + itemId; }
    public static String fluidPort(String fluidId) { return FLUID_PREFIX + fluidId; }

    public static int clampRate(int rate) {
        return Math.max(1, Math.min(100000, rate));
    }

    public static int clampSeconds(int seconds) {
        return Math.max(1, Math.min(86400, seconds));
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("PageId", pageId);
        tag.putString("From", fromNodeId);
        tag.putString("To", toNodeId);
        tag.putString("FromPort", fromPortKey);
        tag.putString("ToPort", toPortKey);
        tag.putBoolean("Enabled", enabled);
        ListTag rows = new ListTag();
        for (ItemRate row : itemRates.values()) {
            if (!row.configured) continue;
            CompoundTag item = new CompoundTag();
            item.putString("Item", row.itemId);
            item.putBoolean("RateLimitEnabled", row.rateLimitEnabled);
            item.putInt("RateLimitSeconds", row.rateLimitSeconds);
            item.putInt("RateLimitItems", row.rateLimitItems);
            rows.add(item);
        }
        tag.put("ItemRates", rows);
        return tag;
    }

    public static TransferEdge load(CompoundTag tag, String defaultPageId) {
        TransferEdge edge = new TransferEdge(
                tag.getString("Id"),
                tag.contains("PageId") ? tag.getString("PageId") : defaultPageId,
                tag.getString("From"),
                tag.getString("To"),
                tag.getString("FromPort"),
                tag.getString("ToPort"),
                false,
                1,
                64,
                tag.getBoolean("Enabled")
        );
        ListTag rows = tag.getList("ItemRates", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag row = rows.getCompound(i);
            edge.setItemRate(row.getString("Item"), row.getBoolean("RateLimitEnabled"), row.getInt("RateLimitSeconds"), row.getInt("RateLimitItems"));
        }
        return edge;
    }

    private String syntheticItemId() {
        return isItemPort() || isFluidPort() ? resourceId() : "";
    }

    private boolean validItemId(String itemId) {
        if (itemId == null) return false;
        if (itemId.startsWith(ITEM_PREFIX)) {
            ResourceLocation id = ResourceLocation.tryParse(itemId.substring(ITEM_PREFIX.length()));
            return id != null && BuiltInRegistries.ITEM.get(id) != Items.AIR;
        }
        if (itemId.startsWith(FLUID_PREFIX)) {
            if (FLUID_ALL.equals(itemId)) return true;
            ResourceLocation id = ResourceLocation.tryParse(itemId.substring(FLUID_PREFIX.length()));
            return id != null && BuiltInRegistries.FLUID.get(id) != Fluids.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        return id != null && BuiltInRegistries.ITEM.get(id) != Items.AIR;
    }

    private static final class ItemRate {
        private final String itemId;
        private boolean configured;
        private boolean observed;
        private boolean rateLimitEnabled;
        private int rateLimitSeconds = 1;
        private int rateLimitItems = 64;
        private long rateWindowStartGameTime = Long.MIN_VALUE;
        private int movedInRateWindow;
        private long statsWindowStartGameTime = Long.MIN_VALUE;
        private int movedInStatsWindow;
        private int attemptedInStatsWindow;
        private int sourceBlockedInStatsWindow;
        private int receiverBlockedInStatsWindow;
        private int deadlockedWindows;
        private String health = "UNMEASURED";

        private ItemRate(String itemId) {
            this.itemId = itemId;
        }

        private ItemRateSnapshot snapshot() {
            return new ItemRateSnapshot(itemId, rateLimitEnabled, rateLimitSeconds, rateLimitItems, health(), actualRatePerMinute(), configured);
        }

        private boolean hasStats() {
            return observed || statsWindowStartGameTime != Long.MIN_VALUE || movedInStatsWindow > 0 || attemptedInStatsWindow > 0;
        }

        private String health() {
            return health;
        }

        private int actualRatePerMinute() {
            if (statsWindowStartGameTime == Long.MIN_VALUE) return 0;
            return movedInStatsWindow * 3;
        }

        private void refreshRateWindow(long gameTime) {
            long interval = (long) rateLimitSeconds * 20L;
            if (rateWindowStartGameTime == Long.MIN_VALUE || gameTime - rateWindowStartGameTime >= interval) {
                rateWindowStartGameTime = gameTime;
                movedInRateWindow = 0;
            }
        }

        private void refreshStatsWindow(long gameTime) {
            if (statsWindowStartGameTime == Long.MIN_VALUE) {
                statsWindowStartGameTime = gameTime;
                return;
            }
            if (gameTime - statsWindowStartGameTime >= STATS_WINDOW_TICKS) {
                if (movedInStatsWindow == 0 && attemptedInStatsWindow > 0 && receiverBlockedInStatsWindow > 0) deadlockedWindows++;
                else deadlockedWindows = 0;
                statsWindowStartGameTime = gameTime;
                movedInStatsWindow = 0;
                attemptedInStatsWindow = 0;
                sourceBlockedInStatsWindow = 0;
                receiverBlockedInStatsWindow = 0;
                updateHealth();
            }
        }

        private void updateHealth() {
            if (attemptedInStatsWindow <= 0 && movedInStatsWindow <= 0) {
                health = "UNMEASURED";
                return;
            }
            if (deadlockedWindows >= 2) {
                health = "DEADLOCKED";
                return;
            }
            if (!rateLimitEnabled) {
                health = movedInStatsWindow > 0 ? "HEALTHY" : dominantBlockHealth();
                return;
            }
            int configuredPerMinute = Math.max(1, (int) Math.ceil(rateLimitItems * 60.0 / Math.max(1, rateLimitSeconds)));
            health = actualRatePerMinute() >= configuredPerMinute ? "HEALTHY" : dominantBlockHealth();
        }

        private String dominantBlockHealth() {
            if (receiverBlockedInStatsWindow > 0 && receiverBlockedInStatsWindow >= sourceBlockedInStatsWindow) return "RECEIVER_BLOCKED";
            if (sourceBlockedInStatsWindow > 0) return "SOURCE_SHORTAGE";
            return movedInStatsWindow > 0 ? "HEALTHY" : "UNMEASURED";
        }
    }
}
