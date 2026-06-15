package com.pockethomestead.network;

import com.pockethomestead.space.SpaceData;
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
                        SpacePermission.AccessMode mode, List<Member> members) {

    public record Member(UUID id, String name) {}

    private static final SpaceData.TerrainType[] TERRAIN_VALUES = SpaceData.TerrainType.values();
    private static final SpacePermission.AccessMode[] MODE_VALUES = SpacePermission.AccessMode.values();

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
            ByteBufCodecs.VAR_INT.encode(buf, s.members().size());
            for (Member m : s.members()) {
                UUIDUtil.STREAM_CODEC.encode(buf, m.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, m.name());
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
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Member> members = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                String mname = ByteBufCodecs.STRING_UTF8.decode(buf);
                members.add(new Member(id, mname));
            }
            return new SpaceInfo(spaceId, ownerId, name, width, depth, biome, terrain, dimensionId,
                    infinite, amplitude, mode, List.copyOf(members));
        }
    );

    /** 由服务端构建：解析成员 UUID → 名字（profile 缓存）。 */
    public static SpaceInfo from(MinecraftServer server, SpaceData d) {
        SpacePermission perm = d.getPermission();
        List<Member> members = new ArrayList<>();
        for (UUID id : perm.getMembers()) {
            String name = resolveName(server, id);
            members.add(new Member(id, name));
        }
        return new SpaceInfo(d.getSpaceId(), d.getOwnerId(), d.getName(),
                d.getWidth(), d.getDepth(), d.getBiome(), d.getTerrainType(),
                d.getDimensionId().toString(), d.isInfinite(), d.getTerrainAmplitude(),
                perm.getMode(), List.copyOf(members));
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
}
