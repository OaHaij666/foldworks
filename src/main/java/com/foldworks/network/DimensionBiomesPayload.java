package com.foldworks.network;

import com.foldworks.Foldworks;
import com.foldworks.client.ClientDimensionBiomeCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** S→C：返回某维度的可用群系列表，写入客户端缓存。 */
public record DimensionBiomesPayload(ResourceLocation dimension, List<String> biomes) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DimensionBiomesPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "dim_biomes"));

    public static final StreamCodec<ByteBuf, DimensionBiomesPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC,
            DimensionBiomesPayload::dimension,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()),
            DimensionBiomesPayload::biomes,
            DimensionBiomesPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleOnClient(DimensionBiomesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientDimensionBiomeCache.put(payload.dimension().toString(), payload.biomes()));
    }
}
