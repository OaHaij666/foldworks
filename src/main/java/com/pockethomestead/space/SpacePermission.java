package com.pockethomestead.space;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 空间权限。旧的 AccessMode 继续保留用于存档/网络兼容：
 * WHITELIST 表示 Protected，BLACKLIST 表示 Public + 显式 Blocked 成员。
 */
public class SpacePermission {
    public enum AccessMode {
        PRIVATE,   // 仅所有者
        PUBLIC,    // 所有人
        WHITELIST, // 仅名单内
        BLACKLIST  // 名单外的所有人
    }

    public enum AccessLevel {
        NONE,
        VIEW,
        USE,
        WRITE,
        MANAGE;

        public boolean allows(AccessLevel required) {
            if (required == null) return true;
            return ordinal() >= required.ordinal();
        }
    }

    public enum MemberRole {
        ADMIN,
        MEMBER,
        VISITOR,
        BLOCKED;

        public AccessLevel defaultLevel() {
            return switch (this) {
                case ADMIN -> AccessLevel.MANAGE;
                case MEMBER -> AccessLevel.WRITE;
                case VISITOR -> AccessLevel.VIEW;
                case BLOCKED -> AccessLevel.NONE;
            };
        }
    }

    public record MemberRule(UUID id, MemberRole role, AccessLevel overrideLevel) {
        public AccessLevel effectiveLevel() {
            return overrideLevel == null ? role.defaultLevel() : overrideLevel;
        }

        public boolean blocked() {
            return role == MemberRole.BLOCKED || effectiveLevel() == AccessLevel.NONE;
        }
    }

    private AccessMode mode = AccessMode.PRIVATE;
    private AccessLevel protectedLevel = AccessLevel.USE;
    private AccessLevel publicLevel = AccessLevel.VIEW;
    private final Map<UUID, MemberRule> members = new LinkedHashMap<>();

    public AccessMode getMode() { return mode; }
    public void setMode(AccessMode mode) { this.mode = mode == null ? AccessMode.PRIVATE : mode; }

    public AccessLevel getProtectedLevel() { return protectedLevel; }
    public void setProtectedLevel(AccessLevel level) { this.protectedLevel = level == null ? AccessLevel.USE : level; }

    public AccessLevel getPublicLevel() { return publicLevel; }
    public void setPublicLevel(AccessLevel level) { this.publicLevel = level == null ? AccessLevel.VIEW : level; }

    public void addMember(UUID playerId) {
        setMember(playerId, MemberRole.MEMBER, null);
    }

    public void setMember(UUID playerId, MemberRole role, AccessLevel overrideLevel) {
        if (playerId == null) return;
        MemberRole safeRole = role == null ? MemberRole.MEMBER : role;
        members.put(playerId, new MemberRule(playerId, safeRole, overrideLevel));
    }

    public void removeMember(UUID playerId) { members.remove(playerId); }

    public Set<UUID> getMembers() { return Set.copyOf(members.keySet()); }

    public Map<UUID, MemberRule> getMemberRules() { return Map.copyOf(members); }

    public MemberRule getMemberRule(UUID playerId) { return members.get(playerId); }

    public void clearMembers() { members.clear(); }

    public void copyFrom(SpacePermission other) {
        if (other == null) return;
        this.mode = other.mode;
        this.protectedLevel = other.protectedLevel;
        this.publicLevel = other.publicLevel;
        this.members.clear();
        this.members.putAll(other.members);
    }

    public boolean canAccess(UUID playerId, boolean isOwner) {
        return can(playerId, isOwner, AccessLevel.USE);
    }

    public boolean can(UUID playerId, boolean isOwner, AccessLevel required) {
        if (isOwner) return true;
        if (playerId == null) return false;
        AccessLevel actual = levelFor(playerId);
        return actual.allows(required);
    }

    public AccessLevel levelFor(UUID playerId) {
        if (playerId == null) return AccessLevel.NONE;
        MemberRule rule = members.get(playerId);
        if (rule != null) {
            if (rule.blocked()) return AccessLevel.NONE;
            if (rule.overrideLevel() == null && mode == AccessMode.WHITELIST && rule.role() == MemberRole.MEMBER) {
                return protectedLevel;
            }
            return rule.effectiveLevel();
        }
        return switch (mode) {
            case PRIVATE -> AccessLevel.NONE;
            case WHITELIST -> AccessLevel.NONE;
            case PUBLIC -> publicLevel;
            case BLACKLIST -> publicLevel;
        };
    }
}
