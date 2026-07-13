package com.foldworks.network;

import com.foldworks.Foldworks;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** C→S：请求某维度的可用群系列表（群系须来自所选继承维度）。 */
public record RequestDimensionBiomesPayload(ResourceLocation dimension) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestDimensionBiomesPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "request_dim_biomes"));

    public static final StreamCodec<ByteBuf, RequestDimensionBiomesPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC,
            RequestDimensionBiomesPayload::dimension,
            RequestDimensionBiomesPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleOnServer(RequestDimensionBiomesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            List<String> biomes = new ArrayList<>();
            try {
                ServerLevel level = player.server.getLevel(
                        ResourceKey.create(Registries.DIMENSION, payload.dimension()));
                if (level != null) {
                    level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                            .map(h -> h.unwrapKey().orElse(null))
                            .filter(Objects::nonNull)
                            .map(k -> k.location().toString())
                            .distinct()
                            .sorted()
                            .forEach(biomes::add);
                }
            } catch (Exception e) {
                Foldworks.LOGGER.warn("读取维度群系失败: {}", payload.dimension(), e);
            }
            PacketDistributor.sendToPlayer(player, new DimensionBiomesPayload(payload.dimension(), biomes));
        });
    }
}
