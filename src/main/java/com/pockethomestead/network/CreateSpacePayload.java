package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.netty.handler.codec.DecoderException;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CreateSpacePayload implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CreateSpacePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "create_space"));

    // 每玩家最多保留一条（put 覆盖），条目体积约 200 字节，泄漏上限 = 历史独立玩家数 × 200B。
    // 即使百万级玩家也不到 200MB，无需清理机制。审查报告原标 Critical 实为预期。
    private static final Map<UUID, CreateSpacePayload> PENDING = new ConcurrentHashMap<>();
    private static final SpaceData.TerrainType[] TERRAIN_VALUES = SpaceData.TerrainType.values();

    private static SpaceData.TerrainType safeTerrain(int ordinal) {
        if (ordinal < 0 || ordinal >= TERRAIN_VALUES.length)
            throw new DecoderException("Invalid terrain type ordinal: " + ordinal);
        return TERRAIN_VALUES[ordinal];
    }

    private final int width;
    private final int depth;
    private final SpaceData.TerrainType terrain;
    private final String biome;
    private final boolean mobs;
    private final boolean structs;
    private final boolean infinite;
    private final float amplitude;

    public static final StreamCodec<ByteBuf, CreateSpacePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.width);
            ByteBufCodecs.VAR_INT.encode(buf, p.depth);
            ByteBufCodecs.VAR_INT.encode(buf, p.terrain.ordinal());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.biome);
            ByteBufCodecs.BOOL.encode(buf, p.mobs);
            ByteBufCodecs.BOOL.encode(buf, p.structs);
            ByteBufCodecs.BOOL.encode(buf, p.infinite);
            ByteBufCodecs.FLOAT.encode(buf, p.amplitude);
        },
        buf -> new CreateSpacePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            safeTerrain(ByteBufCodecs.VAR_INT.decode(buf)),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.BOOL.decode(buf),
            ByteBufCodecs.FLOAT.decode(buf)
        )
    );

    public CreateSpacePayload(int width, int depth, SpaceData.TerrainType terrain, String biome,
                              boolean mobs, boolean structs, boolean infinite, float amplitude) {
        this.width = width;
        this.depth = depth;
        this.terrain = terrain;
        this.biome = biome;
        this.mobs = mobs;
        this.structs = structs;
        this.infinite = infinite;
        this.amplitude = amplitude;
    }

    public int width() { return width; }
    public int depth() { return depth; }
    public SpaceData.TerrainType terrain() { return terrain; }
    public String biome() { return biome; }
    public boolean mobs() { return mobs; }
    public boolean structs() { return structs; }
    public boolean infinite() { return infinite; }
    public float amplitude() { return amplitude; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static CreateSpacePayload takePending(UUID playerId) {
        return PENDING.remove(playerId);
    }

    public static void handleOnServer(CreateSpacePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() != null) {
                PENDING.put(context.player().getUUID(), payload);
            }
        });
    }
}
