package com.pockethomestead.space;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 空间权限：四种访问模式。WHITELIST/BLACKLIST 共用一份 members 名单。
 */
public class SpacePermission {
    public enum AccessMode {
        PRIVATE,   // 仅所有者
        PUBLIC,    // 所有人
        WHITELIST, // 仅名单内
        BLACKLIST  // 名单外的所有人
    }

    private AccessMode mode = AccessMode.PRIVATE;
    private final Set<UUID> members = new LinkedHashSet<>();

    public AccessMode getMode() { return mode; }
    public void setMode(AccessMode mode) { this.mode = mode; }

    public void addMember(UUID playerId) { members.add(playerId); }
    public void removeMember(UUID playerId) { members.remove(playerId); }
    public Set<UUID> getMembers() { return Set.copyOf(members); }
    public void clearMembers() { members.clear(); }

    public boolean canAccess(UUID playerId, boolean isOwner) {
        if (isOwner) return true;
        return switch (mode) {
            case PRIVATE -> false;
            case PUBLIC -> true;
            case WHITELIST -> members.contains(playerId);
            case BLACKLIST -> !members.contains(playerId);
        };
    }
}
