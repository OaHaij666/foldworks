package com.pockethomestead.network;

import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceChunkLoadingManager;
import com.pockethomestead.space.SpacePermission;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import io.netty.handler.codec.DecoderException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 客户端安全的空间 DTO（含权限模式与成员名，用于权限编辑界面展示）。 */
public record SpaceInfo(UUID spaceId, UUID ownerId, String name, int width, int depth,
                        String biome, SpaceData.TerrainType terrain, String dimensionId,
                        boolean infinite, float amplitude,
                        SpacePermission.AccessMode mode,
                        SpacePermission.AccessLevel protectedLevel,
        SpacePermission.AccessLevel publicLevel,
        boolean offlineSimulationEnabled,
        boolean chunkLoadingEnabled,
        boolean chunkLoadingAllowed,
        List<Member> members) {
    public static final UUID OWNER_PERMISSION_ID = new UUID(0, 0);

    public record Member(UUID id, String name, SpacePermission.MemberRole role, SpacePermission.AccessLevel overrideLevel) {
        public SpacePermission.AccessLevel effectiveLevel() {
            return overrideLevel == null ? role.defaultLevel() : overrideLevel;
        }
    }

    private static final SpaceData.TerrainType[] TERRAIN_VALUES = SpaceData.TerrainType.values();
    private static final SpacePermission.AccessMode[] MODE_VALUES = SpacePermission.AccessMode.values();
    private static final SpacePermission.AccessLevel[] LEVEL_VALUES = SpacePermission.AccessLevel.values();
    private static final SpacePermission.MemberRole[] ROLE_VALUES = SpacePermission.MemberRole.values();

    private static SpaceData.TerrainType safeTerrain(int ordinal) {
        if (ordinal < 0 || ordinal >= TERRAIN_VALUES.length)
            throw new DecoderException("Invalid terrain type ordinal: " + ordinal);
        return TERRAIN_VALUES[ordinal];
    }

    private static SpacePermission.AccessMode safeMode(int ordinal) {
        if (ordinal < 0 || ordinal >= MODE_VALUES.length)
            throw new DecoderException("Invalid access mode ordinal: " + ordinal);
        return MODE_VALUES[ordinal];
    }

    private static SpacePermission.AccessLevel safeLevel(int ordinal) {
        if (ordinal < 0 || ordinal >= LEVEL_VALUES.length)
            throw new DecoderException("Invalid access level ordinal: " + ordinal);
        return LEVEL_VALUES[ordinal];
    }

    private static SpacePermission.MemberRole safeRole(int ordinal) {
        if (ordinal < 0 || ordinal >= ROLE_VALUES.length)
            throw new DecoderException("Invalid member role ordinal: " + ordinal);
        return ROLE_VALUES[ordinal];
    }

    public static final StreamCodec<ByteBuf, SpaceInfo> STREAM_CODEC = StreamCodec.of(
        (buf, s) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, s.spaceId());
            UUIDUtil.STREAM_CODEC.encode(buf, s.ownerId());
            ByteBufCodecs.STRING_UTF8.encode(buf, s.name());
            ByteBufCodecs.VAR_INT.encode(buf, s.width());
            ByteBufCodecs.VAR_INT.encode(buf, s.depth());
            ByteBufCodecs.STRING_UTF8.encode(buf, s.biome());
            ByteBufCodecs.VAR_INT.encode(buf, s.terrain().ordinal());
            ByteBufCodecs.STRING_UTF8.encode(buf, s.dimensionId());
            ByteBufCodecs.BOOL.encode(buf, s.infinite());
            ByteBufCodecs.FLOAT.encode(buf, s.amplitude());
            ByteBufCodecs.VAR_INT.encode(buf, s.mode().ordinal());
            ByteBufCodecs.VAR_INT.encode(buf, s.protectedLevel().ordinal());
            ByteBufCodecs.VAR_INT.encode(buf, s.publicLevel().ordinal());
            ByteBufCodecs.BOOL.encode(buf, s.offlineSimulationEnabled());
            ByteBufCodecs.BOOL.encode(buf, s.chunkLoadingEnabled());
            ByteBufCodecs.BOOL.encode(buf, s.chunkLoadingAllowed());
            ByteBufCodecs.VAR_INT.encode(buf, s.members().size());
            for (Member m : s.members()) {
                UUIDUtil.STREAM_CODEC.encode(buf, m.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, m.name());
                ByteBufCodecs.VAR_INT.encode(buf, m.role().ordinal());
                ByteBufCodecs.VAR_INT.encode(buf, m.overrideLevel() == null ? -1 : m.overrideLevel().ordinal());
            }
        },
        buf -> {
            UUID spaceId = UUIDUtil.STREAM_CODEC.decode(buf);
            UUID ownerId = UUIDUtil.STREAM_CODEC.decode(buf);
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            int width = ByteBufCodecs.VAR_INT.decode(buf);
            int depth = ByteBufCodecs.VAR_INT.decode(buf);
            String biome = ByteBufCodecs.STRING_UTF8.decode(buf);
            SpaceData.TerrainType terrain = safeTerrain(ByteBufCodecs.VAR_INT.decode(buf));
            String dimensionId = ByteBufCodecs.STRING_UTF8.decode(buf);
            boolean infinite = ByteBufCodecs.BOOL.decode(buf);
            float amplitude = ByteBufCodecs.FLOAT.decode(buf);
            SpacePermission.AccessMode mode = safeMode(ByteBufCodecs.VAR_INT.decode(buf));
            SpacePermission.AccessLevel protectedLevel = safeLevel(ByteBufCodecs.VAR_INT.decode(buf));
            SpacePermission.AccessLevel publicLevel = safeLevel(ByteBufCodecs.VAR_INT.decode(buf));
            boolean offlineSimulationEnabled = ByteBufCodecs.BOOL.decode(buf);
            boolean chunkLoadingEnabled = ByteBufCodecs.BOOL.decode(buf);
            boolean chunkLoadingAllowed = ByteBufCodecs.BOOL.decode(buf);
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Member> members = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                String mname = ByteBufCodecs.STRING_UTF8.decode(buf);
                SpacePermission.MemberRole role = safeRole(ByteBufCodecs.VAR_INT.decode(buf));
                int overrideOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
                SpacePermission.AccessLevel override = overrideOrdinal < 0 ? null : safeLevel(overrideOrdinal);
                members.add(new Member(id, mname, role, override));
            }
            return new SpaceInfo(spaceId, ownerId, name, width, depth, biome, terrain, dimensionId,
                    infinite, amplitude, mode, protectedLevel, publicLevel, offlineSimulationEnabled,
                    chunkLoadingEnabled, chunkLoadingAllowed, List.copyOf(members));
        }
    );

    /** 由服务端构建：解析成员 UUID → 名字（profile 缓存）。 */
    public static SpaceInfo from(MinecraftServer server, SpaceData d) {
        SpacePermission perm = d.getPermission();
        return fromPermission(server, d.getSpaceId(), d.getOwnerId(), d.getName(), d.getWidth(), d.getDepth(),
                d.getBiome(), d.getTerrainType(), d.getDimensionId().toString(), d.isInfinite(), d.getTerrainAmplitude(),
                d.isOfflineSimulationEnabled(), d.isChunkLoadingEnabled() && SpaceChunkLoadingManager.canEnable(d),
                SpaceChunkLoadingManager.canEnable(d), perm);
    }

    public static SpaceInfo ownerPermission(MinecraftServer server, UUID ownerId, SpacePermission permission) {
        return fromPermission(server, OWNER_PERMISSION_ID, ownerId, "我的私有权限", 0, 0, "",
                SpaceData.TerrainType.FLAT, "", false, 0.0f, false, false, false, permission);
    }

    private static SpaceInfo fromPermission(MinecraftServer server, UUID spaceId, UUID ownerId, String name,
                                            int width, int depth, String biome, SpaceData.TerrainType terrain,
                                            String dimensionId, boolean infinite, float amplitude,
                                            boolean offlineSimulationEnabled, boolean chunkLoadingEnabled,
                                            boolean chunkLoadingAllowed, SpacePermission perm) {
        List<Member> members = new ArrayList<>();
        for (SpacePermission.MemberRule rule : perm.getMemberRules().values()) {
            String memberName = resolveName(server, rule.id());
            members.add(new Member(rule.id(), memberName, rule.role(), rule.overrideLevel()));
        }
        return new SpaceInfo(spaceId, ownerId, name, width, depth, biome, terrain, dimensionId,
                infinite, amplitude, perm.getMode(), perm.getProtectedLevel(), perm.getPublicLevel(),
                offlineSimulationEnabled, chunkLoadingEnabled, chunkLoadingAllowed, List.copyOf(members));
    }

    private static String resolveName(MinecraftServer server, UUID id) {
        try {
            return server.getProfileCache() != null
                    ? server.getProfileCache().get(id).map(p -> p.getName()).orElse(id.toString().substring(0, 8))
                    : id.toString().substring(0, 8);
        } catch (Exception e) {
            return id.toString().substring(0, 8);
        }
    }

    public boolean isOwner(UUID playerId) {
        return ownerId.equals(playerId);
    }

    public SpacePermission.AccessLevel effectiveLevel(UUID playerId) {
        if (playerId == null) return SpacePermission.AccessLevel.NONE;
        if (isOwner(playerId)) return SpacePermission.AccessLevel.MANAGE;
        for (Member member : members) {
            if (member.id().equals(playerId)) {
                if (member.role() == SpacePermission.MemberRole.BLOCKED) return SpacePermission.AccessLevel.NONE;
                if (member.overrideLevel() != null) return member.overrideLevel();
                if (member.role() == SpacePermission.MemberRole.MEMBER && mode == SpacePermission.AccessMode.WHITELIST) {
                    return protectedLevel;
                }
                return member.role().defaultLevel();
            }
        }
        return switch (mode) {
            case PRIVATE, WHITELIST -> SpacePermission.AccessLevel.NONE;
            case PUBLIC, BLACKLIST -> publicLevel;
        };
    }
}
