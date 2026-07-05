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
    private final Map<UUID, SpacePermission.AccessLevel> invitations = new LinkedHashMap<>();

    public TransferTeam(UUID id, String name, UUID owner) {
        this.id = id == null ? UUID.randomUUID() : id;
        this.name = name == null || name.isBlank() ? "团队" : name;
        this.owner = owner;
        if (owner != null) members.put(owner, SpacePermission.AccessLevel.MANAGE);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public Map<UUID, SpacePermission.AccessLevel> members() { return Map.copyOf(members); }
    public Map<UUID, SpacePermission.AccessLevel> invitations() { return Map.copyOf(invitations); }

    public void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name;
    }

    public void setMember(UUID playerId, SpacePermission.AccessLevel level) {
        if (playerId == null) return;
        if (playerId.equals(owner)) {
            members.put(playerId, SpacePermission.AccessLevel.MANAGE);
            invitations.remove(playerId);
            return;
        }
        if (level == null || level == SpacePermission.AccessLevel.NONE) {
            members.remove(playerId);
            invitations.remove(playerId);
        } else {
            members.put(playerId, normalizeMemberLevel(level));
            invitations.remove(playerId);
        }
    }

    public void inviteMember(UUID playerId, SpacePermission.AccessLevel level) {
        if (playerId == null || playerId.equals(owner) || members.containsKey(playerId)) return;
        invitations.put(playerId, normalizeMemberLevel(level));
    }

    public boolean acceptInvite(UUID playerId) {
        if (playerId == null || members.containsKey(playerId)) return false;
        SpacePermission.AccessLevel level = invitations.remove(playerId);
        if (level == null) return false;
        setMember(playerId, level);
        return true;
    }

    public boolean declineInvite(UUID playerId) {
        return playerId != null && invitations.remove(playerId) != null;
    }

    public boolean hasInvite(UUID playerId) {
        return playerId != null && invitations.containsKey(playerId);
    }

    public SpacePermission.AccessLevel levelFor(UUID playerId) {
        if (playerId == null) return SpacePermission.AccessLevel.NONE;
        if (playerId.equals(owner)) return SpacePermission.AccessLevel.MANAGE;
        return members.getOrDefault(playerId, SpacePermission.AccessLevel.NONE);
    }

    public SpacePermission.AccessLevel invitedLevelFor(UUID playerId) {
        if (playerId == null) return SpacePermission.AccessLevel.NONE;
        return invitations.getOrDefault(playerId, SpacePermission.AccessLevel.NONE);
    }

    public boolean canManageMembers(UUID playerId) {
        return playerId != null && playerId.equals(owner);
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
        ListTag inviteList = new ListTag();
        for (Map.Entry<UUID, SpacePermission.AccessLevel> entry : invitations.entrySet()) {
            CompoundTag invite = new CompoundTag();
            invite.putUUID("Id", entry.getKey());
            invite.putString("Level", entry.getValue().name());
            inviteList.add(invite);
        }
        tag.put("Invitations", inviteList);
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
            if (level != SpacePermission.AccessLevel.NONE) team.setMember(member.getUUID("Id"), level);
        }
        ListTag invites = tag.getList("Invitations", Tag.TAG_COMPOUND);
        for (int i = 0; i < invites.size(); i++) {
            CompoundTag invite = invites.getCompound(i);
            if (!invite.hasUUID("Id")) continue;
            SpacePermission.AccessLevel level = SpacePermission.AccessLevel.VIEW;
            try {
                level = SpacePermission.AccessLevel.valueOf(invite.getString("Level"));
            } catch (IllegalArgumentException ignored) {
            }
            if (level != SpacePermission.AccessLevel.NONE) team.inviteMember(invite.getUUID("Id"), level);
        }
        return team;
    }

    private static SpacePermission.AccessLevel normalizeMemberLevel(SpacePermission.AccessLevel level) {
        if (level == null || level == SpacePermission.AccessLevel.NONE) return SpacePermission.AccessLevel.NONE;
        return level.allows(SpacePermission.AccessLevel.WRITE)
                ? SpacePermission.AccessLevel.WRITE
                : SpacePermission.AccessLevel.VIEW;
    }
}
