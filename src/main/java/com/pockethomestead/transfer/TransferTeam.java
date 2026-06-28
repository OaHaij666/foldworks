package com.pockethomestead.transfer;

import com.pockethomestead.space.SpacePermission;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class TransferTeam {
    private final UUID id;
    private String name;
    private UUID owner;
    private final Map<UUID, SpacePermission.AccessLevel> members = new LinkedHashMap<>();

    public TransferTeam(UUID id, String name, UUID owner) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.name = name == null || name.isBlank() ? "Team" : name;
        this.owner = owner;
        if (owner != null) members.put(owner, SpacePermission.AccessLevel.MANAGE);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public Map<UUID, SpacePermission.AccessLevel> members() { return Map.copyOf(members); }

    public void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name;
    }

    public void setMember(UUID playerId, SpacePermission.AccessLevel level) {
        if (playerId == null) return;
        if (level == null || level == SpacePermission.AccessLevel.NONE) members.remove(playerId);
        else members.put(playerId, level);
    }

    public SpacePermission.AccessLevel levelFor(UUID playerId) {
        if (playerId == null) return SpacePermission.AccessLevel.NONE;
        if (playerId.equals(owner)) return SpacePermission.AccessLevel.MANAGE;
        return members.getOrDefault(playerId, SpacePermission.AccessLevel.NONE);
    }

    public boolean can(UUID playerId, SpacePermission.AccessLevel required) {
        return levelFor(playerId).allows(required);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        if (owner != null) tag.putUUID("Owner", owner);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, SpacePermission.AccessLevel> entry : members.entrySet()) {
            CompoundTag member = new CompoundTag();
            member.putUUID("Id", entry.getKey());
            member.putString("Level", entry.getValue().name());
            list.add(member);
        }
        tag.put("Members", list);
        return tag;
    }

    public static TransferTeam load(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        TransferTeam team = new TransferTeam(id, tag.getString("Name"), owner);
        team.members.clear();
        if (owner != null) team.members.put(owner, SpacePermission.AccessLevel.MANAGE);
        ListTag list = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag member = list.getCompound(i);
            if (!member.hasUUID("Id")) continue;
            SpacePermission.AccessLevel level = SpacePermission.AccessLevel.NONE;
            try {
                level = SpacePermission.AccessLevel.valueOf(member.getString("Level"));
            } catch (IllegalArgumentException ignored) {
            }
            if (level != SpacePermission.AccessLevel.NONE) team.members.put(member.getUUID("Id"), level);
        }
        return team;
    }
}
