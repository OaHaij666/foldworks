package com.pockethomestead.transfer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TransferNode {
    private static final int FLOW_WINDOW_TICKS = 20 * 20;
    public static final int GATE_UNBOUNDED = -1;
    public static final int DEFAULT_GATE_MIN = 0;
    public static final int DEFAULT_GATE_MAX = 64;
    private static final int MAX_GATE_BOUND = 1_000_000_000;

    public record FlowSnapshot(String itemId, int inputRatePerMinute, int outputRatePerMinute, long inputTotal, long outputTotal) {}
    public record ReplenishRule(String itemId, int targetCount) {
        public ReplenishRule {
            itemId = itemId == null ? "" : itemId;
            targetCount = Math.max(1, Math.min(2304, targetCount));
        }
    }

    public enum NodeType {
        CHEST,
        REROUTE,
        LIMIT_GATE,
        JUMP_INPUT,
        JUMP_OUTPUT,
        TRASH,
        PLAYER_INVENTORY;

        public boolean isVirtual() {
            return this == REROUTE || this == LIMIT_GATE || this == JUMP_INPUT || this == JUMP_OUTPUT
                    || this == TRASH || this == PLAYER_INVENTORY;
        }
    }

    private final String id;
    private String pageId;
    private final NodeType type;
    private String chestId;
    private String dimensionKey;
    private BlockPos pos;
    private int x;
    private int y;
    private boolean expanded;
    private boolean enabled;
    private String label;
    private String linkedNodeId;
    private int gateMin;
    private int gateMax;
    private boolean gateCheckSource;
    private final List<String> filterItemIds = new ArrayList<>();
    private final List<String> receiveFilterIds = new ArrayList<>();
    private UUID targetPlayerId;
    private final List<ReplenishRule> replenishRules = new ArrayList<>();
    // flowStats 通过 save()/load() 手动 NBT 持久化（仅保存累计 total，丢弃窗口内值）。不要加 transient——该关键字对 NBT 序列化无效，反而会误导读者以为不持久化。
    private final Map<String, FlowStats> flowStats = new LinkedHashMap<>();

    public TransferNode(String id, String pageId, NodeType type, String chestId, String dimensionKey, BlockPos pos, int x, int y, boolean expanded, boolean enabled, List<String> filterItemIds) {
        this(id, pageId, type, chestId, dimensionKey, pos, x, y, expanded, enabled, filterItemIds, List.of(), null, List.of());
    }

    public TransferNode(String id, String pageId, NodeType type, String chestId, String dimensionKey, BlockPos pos, int x, int y,
                        boolean expanded, boolean enabled, List<String> filterItemIds, List<String> receiveFilterIds,
                        UUID targetPlayerId, List<ReplenishRule> replenishRules) {
        this(id, pageId, type, chestId, dimensionKey, pos, x, y, expanded, enabled, filterItemIds, receiveFilterIds,
                targetPlayerId, replenishRules, "", "", DEFAULT_GATE_MIN, DEFAULT_GATE_MAX);
    }

    public TransferNode(String id, String pageId, NodeType type, String chestId, String dimensionKey, BlockPos pos, int x, int y,
                        boolean expanded, boolean enabled, List<String> filterItemIds, List<String> receiveFilterIds,
                        UUID targetPlayerId, List<ReplenishRule> replenishRules, String label, String linkedNodeId,
                        int gateMin, int gateMax) {
        this(id, pageId, type, chestId, dimensionKey, pos, x, y, expanded, enabled, filterItemIds, receiveFilterIds,
                targetPlayerId, replenishRules, label, linkedNodeId, gateMin, gateMax, false);
    }

    public TransferNode(String id, String pageId, NodeType type, String chestId, String dimensionKey, BlockPos pos, int x, int y,
                        boolean expanded, boolean enabled, List<String> filterItemIds, List<String> receiveFilterIds,
                        UUID targetPlayerId, List<ReplenishRule> replenishRules, String label, String linkedNodeId,
                        int gateMin, int gateMax, boolean gateCheckSource) {
        this.id = id;
        this.pageId = pageId;
        this.type = type == null ? NodeType.CHEST : type;
        this.chestId = chestId == null ? "" : chestId;
        this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
        this.pos = pos == null ? BlockPos.ZERO : pos;
        this.x = x;
        this.y = y;
        this.expanded = expanded;
        this.enabled = enabled;
        this.label = sanitizeLabel(label);
        this.linkedNodeId = linkedNodeId == null ? "" : linkedNodeId;
        this.gateMin = normalizeGateBound(gateMin);
        this.gateMax = normalizeGateBound(gateMax);
        this.gateCheckSource = gateCheckSource;
        if (filterItemIds != null) {
            for (String itemId : filterItemIds) addFilterItem(itemId);
        }
        if (receiveFilterIds != null) {
            for (String itemId : receiveFilterIds) addReceiveFilterItem(itemId);
        }
        this.targetPlayerId = targetPlayerId;
        if (replenishRules != null) {
            java.util.LinkedHashMap<String, ReplenishRule> dedup = new java.util.LinkedHashMap<>();
            for (ReplenishRule rule : replenishRules) {
                if (rule == null || rule.itemId() == null || rule.itemId().isBlank()) continue;
                dedup.put(rule.itemId(), rule);
            }
            this.replenishRules.addAll(dedup.values());
        }
    }

    public String getId() { return id; }
    public String getPageId() { return pageId; }
    public NodeType getNodeType() { return type; }
    public String getChestId() { return chestId; }
    public String getDimensionKey() { return dimensionKey; }
    public BlockPos getPos() { return pos; }
    public int getX() { return x; }
    public int getY() { return y; }
    public boolean isExpanded() { return expanded; }
    public boolean isEnabled() { return enabled; }
    public String getLabel() { return label; }
    public String getLinkedNodeId() { return linkedNodeId; }
    public int getGateMin() { return gateMin; }
    public int getGateMax() { return gateMax; }
    public boolean isGateCheckSource() { return gateCheckSource; }
    public List<String> getFilterItemIds() { return filterItemIds; }
    public List<String> getReceiveFilterIds() { return receiveFilterIds; }
    public UUID getTargetPlayerId() { return targetPlayerId; }
    public List<ReplenishRule> getReplenishRules() { return replenishRules; }

    public void setPageId(String pageId) { this.pageId = pageId; }
    public void setChestId(String chestId) { this.chestId = chestId == null ? "" : chestId; }
    public void setLocation(String dimensionKey, BlockPos pos) {
        this.dimensionKey = dimensionKey == null ? "" : dimensionKey;
        this.pos = pos == null ? BlockPos.ZERO : pos;
    }
    public void setPosition(int x, int y) { this.x = x; this.y = y; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setLabel(String label) { this.label = sanitizeLabel(label); }
    public void setLinkedNodeId(String linkedNodeId) { this.linkedNodeId = linkedNodeId == null ? "" : linkedNodeId; }
    public void setGateRange(int min, int max) {
        this.gateMin = normalizeGateBound(min);
        this.gateMax = normalizeGateBound(max);
    }
    public void setGateCheckSource(boolean gateCheckSource) { this.gateCheckSource = gateCheckSource; }
    public void setTargetPlayerId(UUID targetPlayerId) { this.targetPlayerId = targetPlayerId; }

    public void addFilterItem(String itemId) {
        if (itemId != null && !itemId.isBlank() && !filterItemIds.contains(itemId)) filterItemIds.add(itemId);
    }

    public void removeFilterItem(String itemId) { filterItemIds.remove(itemId); }

    public void addReceiveFilterItem(String itemId) {
        if (itemId != null && !itemId.isBlank() && !receiveFilterIds.contains(itemId)) receiveFilterIds.add(itemId);
    }

    public void removeReceiveFilterItem(String itemId) { receiveFilterIds.remove(itemId); }

    public void setReplenishRule(String itemId, int targetCount) {
        if (itemId == null || itemId.isBlank()) return;
        ReplenishRule rule = new ReplenishRule(itemId, targetCount);
        for (int i = 0; i < replenishRules.size(); i++) {
            if (replenishRules.get(i).itemId().equals(rule.itemId())) {
                replenishRules.set(i, rule);
                return;
            }
        }
        replenishRules.add(rule);
    }

    public void removeReplenishRule(String itemId) {
        replenishRules.removeIf(rule -> rule.itemId().equals(itemId));
    }

    public int targetCountFor(String itemId, int defaultStackSize) {
        if (itemId == null || itemId.isBlank()) return 0;
        if (type != NodeType.PLAYER_INVENTORY) return Integer.MAX_VALUE;
        if (replenishRules.isEmpty()) return Math.max(1, defaultStackSize);
        for (ReplenishRule rule : replenishRules) {
            if (rule.itemId().equals(itemId)) return rule.targetCount();
        }
        return 0;
    }

    public boolean isGateRangeValid() {
        return gateMin == GATE_UNBOUNDED || gateMax == GATE_UNBOUNDED || gateMin <= gateMax;
    }

    public int gateBudgetWithinPassRange(int amount, int routeBudget) {
        if (amount < 0 || routeBudget <= 0) return 0;
        if (gateMin != GATE_UNBOUNDED && amount < gateMin) return 0;
        if (gateMax == GATE_UNBOUNDED) return routeBudget;
        if (amount >= gateMax) return 0;
        return Math.max(0, Math.min(routeBudget, gateMax - amount));
    }

    public int sourceGateBudgetWithinPassRange(int amount, int routeBudget) {
        if (amount < 0 || routeBudget <= 0) return 0;
        if (gateMin != GATE_UNBOUNDED && amount < gateMin) return 0;
        if (gateMax != GATE_UNBOUNDED && amount > gateMax) return 0;
        return routeBudget;
    }

    public void recordFlowInput(long gameTime, String itemId, int amount) {
        if (itemId == null || itemId.isBlank() || amount <= 0) return;
        FlowStats stats = flowStats.computeIfAbsent(itemId, FlowStats::new);
        stats.refresh(gameTime);
        stats.inputInWindow += amount;
        stats.inputTotal += amount;
    }

    public void recordFlowOutput(long gameTime, String itemId, int amount) {
        if (itemId == null || itemId.isBlank() || amount <= 0) return;
        FlowStats stats = flowStats.computeIfAbsent(itemId, FlowStats::new);
        stats.refresh(gameTime);
        stats.outputInWindow += amount;
        stats.outputTotal += amount;
    }

    public List<FlowSnapshot> getFlowStats() {
        List<FlowSnapshot> snapshots = new ArrayList<>();
        for (FlowStats stats : flowStats.values()) snapshots.add(stats.snapshot());
        return snapshots;
    }

    public void copyFlowStatsFrom(TransferNode other) {
        if (other == null) return;
        flowStats.clear();
        for (FlowStats source : other.flowStats.values()) {
            FlowStats copy = new FlowStats(source.itemId);
            copy.windowStartGameTime = source.windowStartGameTime;
            copy.inputInWindow = source.inputInWindow;
            copy.outputInWindow = source.outputInWindow;
            copy.inputTotal = source.inputTotal;
            copy.outputTotal = source.outputTotal;
            flowStats.put(copy.itemId, copy);
        }
    }

    public boolean matches(String chestId, String dimensionKey, BlockPos pos) {
        return this.type == NodeType.CHEST && this.chestId.equals(chestId) && this.dimensionKey.equals(dimensionKey) && this.pos.equals(pos);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("PageId", pageId);
        tag.putString("Type", type.name());
        tag.putString("ChestId", chestId);
        tag.putString("Dimension", dimensionKey);
        tag.putLong("Pos", pos.asLong());
        tag.putInt("X", x);
        tag.putInt("Y", y);
        tag.putBoolean("Expanded", expanded);
        tag.putBoolean("Enabled", enabled);
        tag.putString("Label", label);
        tag.putString("LinkedNode", linkedNodeId);
        tag.putInt("GateMin", gateMin);
        tag.putInt("GateMax", gateMax);
        tag.putBoolean("GateCheckSource", gateCheckSource);
        if (type == NodeType.LIMIT_GATE) tag.putBoolean("GatePassRange", true);
        ListTag filters = new ListTag();
        for (String itemId : filterItemIds) {
            CompoundTag item = new CompoundTag();
            item.putString("Id", itemId);
            filters.add(item);
        }
        tag.put("FilterItems", filters);
        ListTag receiveFilters = new ListTag();
        for (String itemId : receiveFilterIds) {
            CompoundTag item = new CompoundTag();
            item.putString("Id", itemId);
            receiveFilters.add(item);
        }
        tag.put("ReceiveFilterItems", receiveFilters);
        if (targetPlayerId != null) tag.putUUID("TargetPlayer", targetPlayerId);
        ListTag replenish = new ListTag();
        for (ReplenishRule rule : replenishRules) {
            CompoundTag item = new CompoundTag();
            item.putString("Id", rule.itemId());
            item.putInt("Target", rule.targetCount());
            replenish.add(item);
        }
        tag.put("ReplenishRules", replenish);
        ListTag flows = new ListTag();
        for (FlowStats stats : flowStats.values()) {
            if (stats.inputTotal <= 0 && stats.outputTotal <= 0) continue;
            CompoundTag flow = new CompoundTag();
            flow.putString("Item", stats.itemId);
            flow.putLong("InputTotal", stats.inputTotal);
            flow.putLong("OutputTotal", stats.outputTotal);
            flows.add(flow);
        }
        tag.put("FlowStats", flows);
        return tag;
    }

    public static TransferNode load(CompoundTag tag, String defaultPageId) {
        String typeName = tag.getString("Type");
        if ("SUPPLY".equals(typeName) || "PICKUP".equals(typeName)) return null;
        NodeType type;
        try {
            type = NodeType.valueOf(typeName);
        } catch (Exception e) {
            type = NodeType.CHEST;
        }
        List<String> filters = new ArrayList<>();
        ListTag filterList = tag.getList("FilterItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < filterList.size(); i++) filters.add(filterList.getCompound(i).getString("Id"));
        List<String> receiveFilters = new ArrayList<>();
        ListTag receiveFilterList = tag.getList("ReceiveFilterItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < receiveFilterList.size(); i++) receiveFilters.add(receiveFilterList.getCompound(i).getString("Id"));
        UUID targetPlayerId = tag.hasUUID("TargetPlayer") ? tag.getUUID("TargetPlayer") : null;
        List<ReplenishRule> replenishRules = new ArrayList<>();
        ListTag replenishList = tag.getList("ReplenishRules", Tag.TAG_COMPOUND);
        for (int i = 0; i < replenishList.size(); i++) {
            CompoundTag rule = replenishList.getCompound(i);
            replenishRules.add(new ReplenishRule(rule.getString("Id"), rule.getInt("Target")));
        }
        int gateMin = tag.contains("GateMin") ? tag.getInt("GateMin") : DEFAULT_GATE_MIN;
        int gateMax = tag.contains("GateMax") ? tag.getInt("GateMax") : DEFAULT_GATE_MAX;
        if (type == NodeType.LIMIT_GATE && tag.contains("GateMin") && tag.contains("GateMax") && !tag.contains("GatePassRange")) {
            int legacyMin = gateMin;
            int legacyMax = gateMax;
            gateMin = legacyMax == GATE_UNBOUNDED || legacyMin != GATE_UNBOUNDED ? 0 : (int) Math.min(MAX_GATE_BOUND, (long) legacyMax + 1L);
            gateMax = legacyMax == GATE_UNBOUNDED ? legacyMin : legacyMin == GATE_UNBOUNDED ? GATE_UNBOUNDED : legacyMin;
        }
        TransferNode node = new TransferNode(
                tag.getString("Id"),
                tag.contains("PageId") ? tag.getString("PageId") : defaultPageId,
                type,
                tag.getString("ChestId"),
                tag.getString("Dimension"),
                BlockPos.of(tag.getLong("Pos")),
                tag.getInt("X"),
                tag.getInt("Y"),
                tag.getBoolean("Expanded"),
                !tag.contains("Enabled") || tag.getBoolean("Enabled"),
                filters,
                receiveFilters,
                targetPlayerId,
                replenishRules,
                tag.getString("Label"),
                tag.getString("LinkedNode"),
                gateMin,
                gateMax,
                tag.getBoolean("GateCheckSource")
        );
        ListTag flowList = tag.getList("FlowStats", Tag.TAG_COMPOUND);
        for (int i = 0; i < flowList.size(); i++) {
            CompoundTag flowTag = flowList.getCompound(i);
            String itemId = flowTag.getString("Item");
            if (itemId == null || itemId.isBlank()) continue;
            FlowStats stats = node.flowStats.computeIfAbsent(itemId, FlowStats::new);
            stats.inputTotal = flowTag.getLong("InputTotal");
            stats.outputTotal = flowTag.getLong("OutputTotal");
        }
        return node;
    }

    private static String sanitizeLabel(String label) {
        if (label == null) return "";
        label = label.trim();
        return label.length() > 24 ? label.substring(0, 24) : label;
    }

    private static int normalizeGateBound(int value) {
        if (value < 0) return GATE_UNBOUNDED;
        return Math.min(MAX_GATE_BOUND, value);
    }

    private static final class FlowStats {
        private final String itemId;
        private long windowStartGameTime = Long.MIN_VALUE;
        private int inputInWindow;
        private int outputInWindow;
        private long inputTotal;
        private long outputTotal;

        private FlowStats(String itemId) {
            this.itemId = itemId;
        }

        private void refresh(long gameTime) {
            if (windowStartGameTime == Long.MIN_VALUE) {
                windowStartGameTime = gameTime;
                return;
            }
            if (gameTime - windowStartGameTime >= FLOW_WINDOW_TICKS) {
                windowStartGameTime = gameTime;
                inputInWindow = 0;
                outputInWindow = 0;
            }
        }

        private FlowSnapshot snapshot() {
            return new FlowSnapshot(itemId, inputInWindow * 3, outputInWindow * 3, inputTotal, outputTotal);
        }
    }
}
