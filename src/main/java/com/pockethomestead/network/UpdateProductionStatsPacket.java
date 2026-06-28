package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.production.ProductionStatsStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record UpdateProductionStatsPacket(String action, List<String> values) implements CustomPacketPayload {
    public static final Type<UpdateProductionStatsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "update_production_stats"));

    public static final StreamCodec<ByteBuf, UpdateProductionStatsPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public UpdateProductionStatsPacket decode(ByteBuf buf) {
            String action = ByteBufCodecs.STRING_UTF8.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < count; i++) values.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            return new UpdateProductionStatsPacket(action, values);
        }

        @Override
        public void encode(ByteBuf buf, UpdateProductionStatsPacket packet) {
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.action);
            ByteBufCodecs.VAR_INT.encode(buf, packet.values.size());
            for (String value : packet.values) ByteBufCodecs.STRING_UTF8.encode(buf, value);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UpdateProductionStatsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ProductionStatsStorage storage = ProductionStatsStorage.get(player.server);
            boolean changed = apply(packet, player, storage);
            if (changed) {
                RequestProductionStatsPacket.sendTo(player);
                if (player.containerMenu instanceof BaseChestMenu menu && menu.getBlockEntity() != null) {
                    ChestConfigPacket.sendSyncToClient(player, menu.getBlockEntity());
                }
            }
        });
    }

    private static boolean apply(UpdateProductionStatsPacket packet, ServerPlayer player, ProductionStatsStorage storage) {
        List<String> v = packet.values;
        return switch (packet.action) {
            case "SET_CURRENT_CHEST_GROUP" -> {
                if (!(player.containerMenu instanceof BaseChestMenu menu)) yield false;
                BaseChestBlockEntity be = menu.getBlockEntity();
                if (be == null || be.getOwnerUUID() == null || be.getLevel() == null) yield false;
                String groupId = v.isEmpty() ? "" : v.get(0);
                String key = ProductionStatsStorage.chestKey(be.getLevel().dimension().location().toString(), be.getBlockPos());
                yield storage.setChestGroup(be.getOwnerUUID(), key, groupId, itemSnapshot(be));
            }
            case "CREATE_GROUP" -> {
                String name = v.isEmpty() ? "新分组" : v.get(0);
                boolean aggregate = v.size() > 1 && Boolean.parseBoolean(v.get(1));
                String created = storage.createGroup(player.getUUID(), name, aggregate);
                if (!aggregate && v.size() > 2 && "assign_current".equals(v.get(2))
                        && player.containerMenu instanceof BaseChestMenu menu && menu.getBlockEntity() != null) {
                    BaseChestBlockEntity be = menu.getBlockEntity();
                    if (be.getOwnerUUID() != null && be.getLevel() != null) {
                        String key = ProductionStatsStorage.chestKey(be.getLevel().dimension().location().toString(), be.getBlockPos());
                        storage.setChestGroup(be.getOwnerUUID(), key, created, itemSnapshot(be));
                    }
                }
                yield true;
            }
            case "RENAME_GROUP" -> {
                if (v.size() < 2) yield false;
                yield storage.renameGroup(player.getUUID(), v.get(0), v.get(1));
            }
            case "DELETE_GROUP" -> {
                if (v.isEmpty()) yield false;
                yield storage.deleteGroup(player.getUUID(), v.get(0));
            }
            case "MERGE_GROUPS" -> {
                if (v.size() < 3) yield false;
                yield storage.mergeGroups(player.getUUID(), v.get(0), v.subList(1, v.size()));
            }
            case "TOGGLE_CHILD" -> {
                if (v.size() < 2) yield false;
                yield storage.toggleChild(player.getUUID(), v.get(0), v.get(1));
            }
            case "TOGGLE_FAVORITE_RESOURCE" -> {
                if (v.isEmpty()) yield false;
                yield storage.toggleFavoriteResource(player.getUUID(), v.get(0));
            }
            default -> false;
        };
    }

    private static Map<String, Integer> itemSnapshot(BaseChestBlockEntity be) {
        return be.productionResourceSnapshot();
    }
}
