package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.permission.AccessControl;
import com.pockethomestead.production.ProductionStatsStorage;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferGraphAccess;
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

public record UpdateProductionStatsPacket(String scopeKind, String scopeId, String action, List<String> values) implements CustomPacketPayload {
    public static final Type<UpdateProductionStatsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("pockethomestead", "update_production_stats"));

    public UpdateProductionStatsPacket(String action, List<String> values) {
        this(ProductionStatsStorage.PRIVATE_SCOPE, "", action, values);
    }

    public static final StreamCodec<ByteBuf, UpdateProductionStatsPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public UpdateProductionStatsPacket decode(ByteBuf buf) {
            String scopeKind = ByteBufCodecs.STRING_UTF8.decode(buf);
            String scopeId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String action = ByteBufCodecs.STRING_UTF8.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < count; i++) values.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            return new UpdateProductionStatsPacket(scopeKind, scopeId, action, values);
        }

        @Override
        public void encode(ByteBuf buf, UpdateProductionStatsPacket packet) {
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.scopeKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.scopeId);
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
                RequestProductionStatsPacket.sendTo(player, packet.scopeKind(), packet.scopeId());
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
                if (be == null || be.getOwnerUUID() == null || be.getLevel() == null
                        || !AccessControl.canConfigureChest(player, be)) yield false;
                String scopeKey = be.productionStatsScopeKey();
                if (scopeKey.isBlank()) yield false;
                String groupId = v.isEmpty() ? "" : v.get(0);
                String key = ProductionStatsStorage.chestKey(be.getLevel().dimension().location().toString(), be.getBlockPos());
                yield storage.setChestGroup(scopeKey, key, groupId, itemSnapshot(be));
            }
            case "CREATE_GROUP" -> {
                String name = v.isEmpty() ? "新分组" : v.get(0);
                boolean aggregate = v.size() > 1 && Boolean.parseBoolean(v.get(1));
                String scopeKey = scopeKeyForMutation(packet, player);
                if (scopeKey.isBlank()) yield false;
                if (!aggregate && v.size() > 2 && "assign_current".equals(v.get(2))
                        && player.containerMenu instanceof BaseChestMenu menu && menu.getBlockEntity() != null) {
                    BaseChestBlockEntity be = menu.getBlockEntity();
                    if (be.getOwnerUUID() != null && be.getLevel() != null && AccessControl.canConfigureChest(player, be)) {
                        String chestScopeKey = be.productionStatsScopeKey();
                        if (!chestScopeKey.isBlank()) scopeKey = chestScopeKey;
                    }
                }
                String created = storage.createGroup(scopeKey, name, aggregate);
                if (!aggregate && v.size() > 2 && "assign_current".equals(v.get(2))
                        && player.containerMenu instanceof BaseChestMenu menu && menu.getBlockEntity() != null) {
                    BaseChestBlockEntity be = menu.getBlockEntity();
                    if (be.getOwnerUUID() != null && be.getLevel() != null && AccessControl.canConfigureChest(player, be)) {
                        String chestScopeKey = be.productionStatsScopeKey();
                        if (chestScopeKey.isBlank()) yield true;
                        String key = ProductionStatsStorage.chestKey(be.getLevel().dimension().location().toString(), be.getBlockPos());
                        storage.setChestGroup(chestScopeKey, key, created, itemSnapshot(be));
                    }
                }
                yield true;
            }
            case "RENAME_GROUP" -> {
                if (v.size() < 2) yield false;
                String scopeKey = scopeKeyForMutation(packet, player);
                yield !scopeKey.isBlank() && storage.renameGroup(scopeKey, v.get(0), v.get(1));
            }
            case "DELETE_GROUP" -> {
                if (v.isEmpty()) yield false;
                String scopeKey = scopeKeyForMutation(packet, player);
                yield !scopeKey.isBlank() && storage.deleteGroup(scopeKey, v.get(0));
            }
            case "MERGE_GROUPS" -> {
                if (v.size() < 3) yield false;
                String scopeKey = scopeKeyForMutation(packet, player);
                yield !scopeKey.isBlank() && storage.mergeGroups(scopeKey, v.get(0), v.subList(1, v.size()));
            }
            case "TOGGLE_CHILD" -> {
                if (v.size() < 2) yield false;
                String scopeKey = scopeKeyForMutation(packet, player);
                yield !scopeKey.isBlank() && storage.toggleChild(scopeKey, v.get(0), v.get(1));
            }
            case "TOGGLE_FAVORITE_RESOURCE" -> {
                if (v.isEmpty()) yield false;
                String scopeKey = scopeKeyForView(packet, player);
                yield !scopeKey.isBlank() && storage.toggleFavoriteResource(scopeKey, v.get(0));
            }
            default -> false;
        };
    }

    private static String scopeKeyForMutation(UpdateProductionStatsPacket packet, ServerPlayer player) {
        return resolveScopeKey(packet.scopeKind(), packet.scopeId(), player, true);
    }

    private static String scopeKeyForView(UpdateProductionStatsPacket packet, ServerPlayer player) {
        return resolveScopeKey(packet.scopeKind(), packet.scopeId(), player, false);
    }

    static String resolveScopeKey(String kind, String idValue, ServerPlayer player, boolean write) {
        GraphKey key = GraphKey.parse(kind, idValue, player.getUUID());
        return switch (key.kind()) {
            case PUBLIC -> "";
            case PRIVATE -> player.getUUID().equals(key.id()) ? ProductionStatsStorage.privateScope(player.getUUID()) : "";
            case PROTECTED -> {
                boolean allowed = write
                        ? TransferGraphAccess.canWrite(player, key)
                        : TransferGraphAccess.canView(player, key);
                yield allowed ? ProductionStatsStorage.teamScope(key.id()) : "";
            }
            case SPACE -> {
                boolean allowed = write
                        ? TransferGraphAccess.canWrite(player, key)
                        : TransferGraphAccess.canView(player, key);
                yield allowed && SpaceManager.getInstance().getSpace(key.id()) != null
                        ? ProductionStatsStorage.spaceScope(key.id())
                        : "";
            }
        };
    }

    private static Map<String, Integer> itemSnapshot(BaseChestBlockEntity be) {
        return be.productionResourceSnapshot();
    }
}
