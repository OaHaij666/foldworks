package com.pockethomestead.transfer;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

public record GraphKey(Kind kind, UUID id) {
    public enum Kind {
        PRIVATE,
        PROTECTED,
        SPACE,
        PUBLIC
    }

    public static final UUID PUBLIC_ID = new UUID(0L, 0L);

    public GraphKey {
        kind = kind == null ? Kind.PRIVATE : kind;
        id = kind == Kind.PUBLIC ? PUBLIC_ID : id;
    }

    public static GraphKey privateGraph(UUID playerId) {
        return new GraphKey(Kind.PRIVATE, playerId);
    }

    public static GraphKey protectedGraph(UUID teamId) {
        return new GraphKey(Kind.PROTECTED, teamId);
    }

    public static GraphKey spaceGraph(UUID spaceId) {
        return new GraphKey(Kind.SPACE, spaceId);
    }

    public static GraphKey publicGraph() {
        return new GraphKey(Kind.PUBLIC, PUBLIC_ID);
    }

    public boolean isValid() {
        return kind == Kind.PUBLIC || id != null;
    }

    public String stableId() {
        return kind.name() + ":" + (id == null ? "" : id);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.name());
        if (id != null) tag.putUUID("Id", id);
        return tag;
    }

    public static GraphKey load(CompoundTag tag, UUID legacyOwner) {
        if (tag.contains("GraphKey")) {
            CompoundTag key = tag.getCompound("GraphKey");
            return loadKeyTag(key, legacyOwner);
        }
        return legacyOwner == null ? publicGraph() : privateGraph(legacyOwner);
    }

    public static GraphKey loadKeyTag(CompoundTag tag, UUID fallbackPrivateOwner) {
        Kind kind = Kind.PRIVATE;
        try {
            if (tag.contains("Kind")) kind = Kind.valueOf(tag.getString("Kind"));
        } catch (IllegalArgumentException ignored) {
            kind = Kind.PRIVATE;
        }
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : fallbackPrivateOwner;
        if (kind == Kind.PUBLIC) return publicGraph();
        return new GraphKey(kind, id);
    }

    public static GraphKey parse(String kindName, String idValue, UUID fallbackPrivateOwner) {
        Kind kind;
        try {
            kind = Kind.valueOf(kindName == null || kindName.isBlank() ? "PRIVATE" : kindName);
        } catch (IllegalArgumentException e) {
            kind = Kind.PRIVATE;
        }
        if (kind == Kind.PUBLIC) return publicGraph();
        UUID id = null;
        try {
            if (idValue != null && !idValue.isBlank()) id = UUID.fromString(idValue);
        } catch (IllegalArgumentException ignored) {
        }
        if (id == null && kind == Kind.PRIVATE) id = fallbackPrivateOwner;
        return new GraphKey(kind, id);
    }

    public boolean sameTier(GraphKey other) {
        return other != null && kind == other.kind && Objects.equals(id, other.id);
    }
}
