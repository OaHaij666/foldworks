package com.pockethomestead.production;

import com.pockethomestead.registry.ChestRegistryManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ProductionStatsStorage extends SavedData {
    public static final String DATA_NAME = "pockethomestead_production_stats";
    public static final String DEFAULT_GROUP_ID = "default";
    public static final int BUCKET_TICKS = 20 * 10;
    public static final int RETENTION_TICKS = 20 * 60 * 60;

    private final Map<UUID, PlayerStats> players = new LinkedHashMap<>();

    public record GroupSnapshot(String id, String name, boolean aggregate, List<String> childIds, int order) {}
    public record BucketSnapshot(String groupId, String itemId, long bucketStart, int input, int output) {}
    public record InventorySnapshot(String groupId, String itemId, int count) {}
    public record MemberSnapshot(String chestKey, String groupId) {}

    public static ProductionStatsStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static String chestKey(ChestRegistryManager.ChestType type, String dimensionKey, BlockPos pos) {
        return type.name() + "|" + dimensionKey + "|" + pos.asLong();
    }

    public static SavedData.Factory<ProductionStatsStorage> factory() {
        return new SavedData.Factory<>(ProductionStatsStorage::new, ProductionStatsStorage::load);
    }

    public PlayerStats statsFor(UUID owner) {
        return players.computeIfAbsent(owner, id -> new PlayerStats());
    }

    public boolean isChestEnabled(UUID owner, String chestKey) {
        PlayerStats stats = players.get(owner);
        return stats != null && stats.chestGroups.containsKey(chestKey);
    }

    public String chestGroup(UUID owner, String chestKey) {
        PlayerStats stats = players.get(owner);
        return stats == null ? "" : stats.chestGroups.getOrDefault(chestKey, "");
    }

    public boolean setChestGroup(UUID owner, String chestKey, String groupId, Map<String, Integer> currentItems) {
        boolean changed = statsFor(owner).setChestGroup(chestKey, groupId, currentItems);
        if (changed) setDirty();
        return changed;
    }

    public void refreshChestInventory(UUID owner, String chestKey, Map<String, Integer> currentItems) {
        if (owner == null || chestKey == null || chestKey.isBlank()) return;
        PlayerStats stats = players.get(owner);
        if (stats == null) return;
        if (stats.refreshChestInventory(chestKey, currentItems)) setDirty();
    }

    public void recordInput(UUID owner, String chestKey, String itemId, int amount, long gameTime) {
        if (owner == null || chestKey == null || itemId == null || amount <= 0) return;
        PlayerStats stats = players.get(owner);
        if (stats != null && stats.record(chestKey, itemId, amount, true, gameTime)) setDirty();
    }

    public void recordOutput(UUID owner, String chestKey, String itemId, int amount, long gameTime) {
        if (owner == null || chestKey == null || itemId == null || amount <= 0) return;
        PlayerStats stats = players.get(owner);
        if (stats != null && stats.record(chestKey, itemId, amount, false, gameTime)) setDirty();
    }

    public String createGroup(UUID owner, String name, boolean aggregate) {
        PlayerStats stats = statsFor(owner);
        String id = UUID.randomUUID().toString();
        stats.groups.put(id, new Group(id, sanitizeName(name, aggregate ? "新聚合组" : "新原子组"), aggregate, stats.groups.size()));
        setDirty();
        return id;
    }

    public boolean renameGroup(UUID owner, String groupId, String name) {
        PlayerStats stats = statsFor(owner);
        Group group = stats.groups.get(groupId);
        if (group == null) return false;
        group.name = sanitizeName(name, group.aggregate ? "聚合组" : "原子组");
        setDirty();
        return true;
    }

    public boolean deleteGroup(UUID owner, String groupId) {
        if (DEFAULT_GROUP_ID.equals(groupId)) return false;
        PlayerStats stats = statsFor(owner);
        if (!stats.groups.containsKey(groupId)) return false;
        stats.deleteGroup(groupId);
        setDirty();
        return true;
    }

    public boolean toggleChild(UUID owner, String parentId, String childId) {
        PlayerStats stats = statsFor(owner);
        if (stats.toggleChild(parentId, childId)) {
            setDirty();
            return true;
        }
        return false;
    }

    public static ProductionStatsStorage load(CompoundTag tag, HolderLookup.Provider reg) {
        ProductionStatsStorage storage = new ProductionStatsStorage();
        ListTag list = tag.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag playerTag = list.getCompound(i);
            UUID owner = playerTag.getUUID("Owner");
            PlayerStats stats = PlayerStats.load(playerTag);
            storage.players.put(owner, stats);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerStats> entry : players.entrySet()) {
            CompoundTag playerTag = entry.getValue().save();
            playerTag.putUUID("Owner", entry.getKey());
            list.add(playerTag);
        }
        tag.put("Players", list);
        return tag;
    }

    private static String sanitizeName(String value, String fallback) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) return fallback;
        return name.length() > 24 ? name.substring(0, 24) : name;
    }

    public static final class PlayerStats {
        private final Map<String, Group> groups = new LinkedHashMap<>();
        private final Map<String, String> chestGroups = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> chestInventories = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> groupInventories = new LinkedHashMap<>();
        private final Map<String, Map<String, LinkedHashMap<Long, Bucket>>> buckets = new LinkedHashMap<>();

        private PlayerStats() {
            ensureDefaultGroup();
        }

        public Collection<GroupSnapshot> groups() {
            ensureDefaultGroup();
            List<GroupSnapshot> rows = new ArrayList<>();
            for (Group group : groups.values()) {
                rows.add(new GroupSnapshot(group.id, group.name, group.aggregate, List.copyOf(group.childIds), group.order));
            }
            rows.sort((a, b) -> Integer.compare(a.order(), b.order()));
            return rows;
        }

        public Collection<BucketSnapshot> buckets(long gameTime) {
            prune(gameTime);
            List<BucketSnapshot> rows = new ArrayList<>();
            long cutoff = gameTime - RETENTION_TICKS;
            for (Map.Entry<String, Map<String, LinkedHashMap<Long, Bucket>>> groupEntry : buckets.entrySet()) {
                Group group = groups.get(groupEntry.getKey());
                if (group == null || group.aggregate) continue;
                for (Map.Entry<String, LinkedHashMap<Long, Bucket>> itemEntry : groupEntry.getValue().entrySet()) {
                    for (Bucket bucket : itemEntry.getValue().values()) {
                        if (bucket.start >= cutoff && (bucket.input > 0 || bucket.output > 0)) {
                            rows.add(new BucketSnapshot(groupEntry.getKey(), itemEntry.getKey(), bucket.start, bucket.input, bucket.output));
                        }
                    }
                }
            }
            return rows;
        }

        public Collection<InventorySnapshot> inventories() {
            List<InventorySnapshot> rows = new ArrayList<>();
            for (Map.Entry<String, Map<String, Integer>> groupEntry : groupInventories.entrySet()) {
                Group group = groups.get(groupEntry.getKey());
                if (group == null || group.aggregate) continue;
                for (Map.Entry<String, Integer> itemEntry : groupEntry.getValue().entrySet()) {
                    if (itemEntry.getValue() > 0) rows.add(new InventorySnapshot(groupEntry.getKey(), itemEntry.getKey(), itemEntry.getValue()));
                }
            }
            return rows;
        }

        public Collection<MemberSnapshot> members() {
            List<MemberSnapshot> rows = new ArrayList<>();
            for (Map.Entry<String, String> entry : chestGroups.entrySet()) rows.add(new MemberSnapshot(entry.getKey(), entry.getValue()));
            return rows;
        }

        private boolean setChestGroup(String chestKey, String groupId, Map<String, Integer> currentItems) {
            ensureDefaultGroup();
            String normalized = groupId == null ? "" : groupId.trim();
            if (!normalized.isEmpty()) {
                Group group = groups.get(normalized);
                if (group == null || group.aggregate) normalized = DEFAULT_GROUP_ID;
            }
            Map<String, Integer> current = normalizeItems(currentItems);
            String oldGroup = chestGroups.get(chestKey);
            Map<String, Integer> oldInventory = chestInventories.getOrDefault(chestKey, Map.of());

            if (oldGroup != null) adjustGroupInventory(oldGroup, oldInventory, -1);
            if (normalized.isEmpty()) {
                chestGroups.remove(chestKey);
                chestInventories.remove(chestKey);
            } else {
                chestGroups.put(chestKey, normalized);
                chestInventories.put(chestKey, current);
                adjustGroupInventory(normalized, current, 1);
            }
            return !equalsNullable(oldGroup, normalized.isEmpty() ? null : normalized) || !oldInventory.equals(current);
        }

        private boolean refreshChestInventory(String chestKey, Map<String, Integer> currentItems) {
            String groupId = chestGroups.get(chestKey);
            if (groupId == null) return false;
            Map<String, Integer> current = normalizeItems(currentItems);
            Map<String, Integer> old = chestInventories.getOrDefault(chestKey, Map.of());
            if (old.equals(current)) return false;
            adjustGroupInventory(groupId, old, -1);
            chestInventories.put(chestKey, current);
            adjustGroupInventory(groupId, current, 1);
            return true;
        }

        private boolean record(String chestKey, String itemId, int amount, boolean input, long gameTime) {
            String groupId = chestGroups.get(chestKey);
            Group group = groups.get(groupId);
            if (group == null || group.aggregate) return false;

            long start = (gameTime / BUCKET_TICKS) * BUCKET_TICKS;
            Bucket bucket = buckets
                    .computeIfAbsent(groupId, id -> new LinkedHashMap<>())
                    .computeIfAbsent(itemId, id -> new LinkedHashMap<>())
                    .computeIfAbsent(start, Bucket::new);
            if (input) bucket.input += amount;
            else bucket.output += amount;

            adjustItem(groupInventories.computeIfAbsent(groupId, id -> new LinkedHashMap<>()), itemId, input ? amount : -amount);
            adjustItem(chestInventories.computeIfAbsent(chestKey, id -> new LinkedHashMap<>()), itemId, input ? amount : -amount);
            prune(gameTime);
            return true;
        }

        private boolean toggleChild(String parentId, String childId) {
            Group parent = groups.get(parentId);
            Group child = groups.get(childId);
            if (parent == null || child == null || !parent.aggregate || parentId.equals(childId)) return false;
            if (parent.childIds.contains(childId)) {
                parent.childIds.remove(childId);
                return true;
            }
            parent.childIds.add(childId);
            if (hasCycle()) {
                parent.childIds.remove(childId);
                return false;
            }
            return true;
        }

        private void deleteGroup(String groupId) {
            Group group = groups.get(groupId);
            if (group == null || DEFAULT_GROUP_ID.equals(groupId)) return;
            for (Group other : groups.values()) other.childIds.remove(groupId);
            if (!group.aggregate) {
                List<String> moved = new ArrayList<>();
                for (Map.Entry<String, String> entry : chestGroups.entrySet()) {
                    if (groupId.equals(entry.getValue())) moved.add(entry.getKey());
                }
                for (String chestKey : moved) {
                    chestGroups.put(chestKey, DEFAULT_GROUP_ID);
                    Map<String, Integer> inv = chestInventories.getOrDefault(chestKey, Map.of());
                    adjustGroupInventory(DEFAULT_GROUP_ID, inv, 1);
                }
                groupInventories.remove(groupId);
                buckets.remove(groupId);
            }
            groups.remove(groupId);
        }

        private void ensureDefaultGroup() {
            groups.computeIfAbsent(DEFAULT_GROUP_ID, id -> new Group(id, "默认", false, 0));
        }

        private void prune(long gameTime) {
            long cutoff = gameTime - RETENTION_TICKS;
            for (Map<String, LinkedHashMap<Long, Bucket>> byItem : buckets.values()) {
                for (LinkedHashMap<Long, Bucket> byTime : byItem.values()) {
                    byTime.entrySet().removeIf(entry -> entry.getKey() < cutoff);
                }
                byItem.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            }
            buckets.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }

        private boolean hasCycle() {
            Set<String> done = new LinkedHashSet<>();
            Set<String> stack = new LinkedHashSet<>();
            for (String groupId : groups.keySet()) if (visitCycle(groupId, done, stack)) return true;
            return false;
        }

        private boolean visitCycle(String groupId, Set<String> done, Set<String> stack) {
            if (done.contains(groupId)) return false;
            if (!stack.add(groupId)) return true;
            Group group = groups.get(groupId);
            if (group != null) {
                for (String child : group.childIds) if (visitCycle(child, done, stack)) return true;
            }
            stack.remove(groupId);
            done.add(groupId);
            return false;
        }

        private void adjustGroupInventory(String groupId, Map<String, Integer> items, int sign) {
            if (groupId == null || items.isEmpty()) return;
            Map<String, Integer> target = groupInventories.computeIfAbsent(groupId, id -> new LinkedHashMap<>());
            for (Map.Entry<String, Integer> entry : items.entrySet()) {
                adjustItem(target, entry.getKey(), entry.getValue() * sign);
            }
        }

        private static void adjustItem(Map<String, Integer> items, String itemId, int delta) {
            if (itemId == null || itemId.isBlank() || delta == 0) return;
            int next = Math.max(0, items.getOrDefault(itemId, 0) + delta);
            if (next <= 0) items.remove(itemId);
            else items.put(itemId, next);
        }

        private static Map<String, Integer> normalizeItems(Map<String, Integer> items) {
            Map<String, Integer> normalized = new LinkedHashMap<>();
            if (items == null) return normalized;
            for (Map.Entry<String, Integer> entry : items.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null && entry.getValue() > 0) {
                    normalized.put(entry.getKey(), entry.getValue());
                }
            }
            return normalized;
        }

        private static boolean equalsNullable(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        private CompoundTag save() {
            ensureDefaultGroup();
            CompoundTag tag = new CompoundTag();

            ListTag groupsTag = new ListTag();
            for (Group group : groups.values()) groupsTag.add(group.save());
            tag.put("Groups", groupsTag);

            ListTag membersTag = new ListTag();
            for (Map.Entry<String, String> entry : chestGroups.entrySet()) {
                CompoundTag member = new CompoundTag();
                member.putString("ChestKey", entry.getKey());
                member.putString("GroupId", entry.getValue());
                membersTag.add(member);
            }
            tag.put("Members", membersTag);

            ListTag inventoriesTag = new ListTag();
            for (Map.Entry<String, Map<String, Integer>> entry : chestInventories.entrySet()) {
                CompoundTag chest = new CompoundTag();
                chest.putString("ChestKey", entry.getKey());
                chest.put("Items", saveItems(entry.getValue()));
                inventoriesTag.add(chest);
            }
            tag.put("ChestInventories", inventoriesTag);

            ListTag bucketTag = new ListTag();
            for (Map.Entry<String, Map<String, LinkedHashMap<Long, Bucket>>> groupEntry : buckets.entrySet()) {
                for (Map.Entry<String, LinkedHashMap<Long, Bucket>> itemEntry : groupEntry.getValue().entrySet()) {
                    for (Bucket bucket : itemEntry.getValue().values()) {
                        CompoundTag row = new CompoundTag();
                        row.putString("GroupId", groupEntry.getKey());
                        row.putString("Item", itemEntry.getKey());
                        row.putLong("Start", bucket.start);
                        row.putInt("Input", bucket.input);
                        row.putInt("Output", bucket.output);
                        bucketTag.add(row);
                    }
                }
            }
            tag.put("Buckets", bucketTag);
            return tag;
        }

        private static PlayerStats load(CompoundTag tag) {
            PlayerStats stats = new PlayerStats();
            stats.groups.clear();
            ListTag groupList = tag.getList("Groups", Tag.TAG_COMPOUND);
            for (int i = 0; i < groupList.size(); i++) {
                Group group = Group.load(groupList.getCompound(i));
                stats.groups.put(group.id, group);
            }
            stats.ensureDefaultGroup();

            ListTag members = tag.getList("Members", Tag.TAG_COMPOUND);
            for (int i = 0; i < members.size(); i++) {
                CompoundTag member = members.getCompound(i);
                String groupId = member.getString("GroupId");
                Group group = stats.groups.get(groupId);
                if (group != null && !group.aggregate) stats.chestGroups.put(member.getString("ChestKey"), groupId);
            }

            ListTag inventories = tag.getList("ChestInventories", Tag.TAG_COMPOUND);
            for (int i = 0; i < inventories.size(); i++) {
                CompoundTag chest = inventories.getCompound(i);
                String chestKey = chest.getString("ChestKey");
                Map<String, Integer> items = loadItems(chest.getList("Items", Tag.TAG_COMPOUND));
                stats.chestInventories.put(chestKey, items);
                String groupId = stats.chestGroups.get(chestKey);
                if (groupId != null) stats.adjustGroupInventory(groupId, items, 1);
            }

            ListTag bucketList = tag.getList("Buckets", Tag.TAG_COMPOUND);
            for (int i = 0; i < bucketList.size(); i++) {
                CompoundTag row = bucketList.getCompound(i);
                String groupId = row.getString("GroupId");
                String itemId = row.getString("Item");
                if (!stats.groups.containsKey(groupId) || itemId.isBlank()) continue;
                Bucket bucket = new Bucket(row.getLong("Start"));
                bucket.input = row.getInt("Input");
                bucket.output = row.getInt("Output");
                stats.buckets.computeIfAbsent(groupId, id -> new LinkedHashMap<>())
                        .computeIfAbsent(itemId, id -> new LinkedHashMap<>())
                        .put(bucket.start, bucket);
            }
            return stats;
        }

        private static ListTag saveItems(Map<String, Integer> items) {
            ListTag list = new ListTag();
            for (Map.Entry<String, Integer> item : items.entrySet()) {
                CompoundTag tag = new CompoundTag();
                tag.putString("Item", item.getKey());
                tag.putInt("Count", item.getValue());
                list.add(tag);
            }
            return list;
        }

        private static Map<String, Integer> loadItems(ListTag list) {
            Map<String, Integer> items = new LinkedHashMap<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                int count = tag.getInt("Count");
                if (count > 0) items.put(tag.getString("Item"), count);
            }
            return items;
        }
    }

    private static final class Group {
        private final String id;
        private String name;
        private final boolean aggregate;
        private final int order;
        private final List<String> childIds = new ArrayList<>();

        private Group(String id, String name, boolean aggregate, int order) {
            this.id = id;
            this.name = name;
            this.aggregate = aggregate;
            this.order = order;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Id", id);
            tag.putString("Name", name);
            tag.putBoolean("Aggregate", aggregate);
            tag.putInt("Order", order);
            ListTag children = new ListTag();
            for (String childId : childIds) {
                CompoundTag child = new CompoundTag();
                child.putString("Id", childId);
                children.add(child);
            }
            tag.put("Children", children);
            return tag;
        }

        private static Group load(CompoundTag tag) {
            Group group = new Group(tag.getString("Id"), tag.getString("Name"), tag.getBoolean("Aggregate"), tag.getInt("Order"));
            ListTag children = tag.getList("Children", Tag.TAG_COMPOUND);
            for (int i = 0; i < children.size(); i++) group.childIds.add(children.getCompound(i).getString("Id"));
            return group;
        }
    }

    private static final class Bucket {
        private final long start;
        private int input;
        private int output;

        private Bucket(long start) {
            this.start = start;
        }
    }
}
