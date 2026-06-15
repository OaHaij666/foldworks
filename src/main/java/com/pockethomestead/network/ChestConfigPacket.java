package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.registry.ChestRegistryManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：更新箱子配置
 * action: 0=切换传送, 1=切换虚空模式, 2=设置ID, 3=设置绑定目标, 4=循环绑定, 5=增加速率, 6=减少速率
 */
public record ChestConfigPacket(int action, String value) implements CustomPacketPayload {
    public static final Type<ChestConfigPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("pockethomestead", "chest_config"));

    public static final StreamCodec<ByteBuf, ChestConfigPacket> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, ChestConfigPacket::action,
        ByteBufCodecs.STRING_UTF8, ChestConfigPacket::value,
        ChestConfigPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * 服务端处理：应用到玩家当前打开的箱子
     */
    public static void handle(ChestConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.containerMenu instanceof BaseChestMenu menu)) return;

            BaseChestBlockEntity be = menu.getBlockEntity();
            if (be == null) return;

            switch (packet.action) {
                case 0 -> { // 切换传送
                    be.setTransferEnabled(!be.isTransferEnabled());
                    sendSyncToClient(player, be);
                }
                case 1 -> { // 切换虚空模式
                    be.setVoidModeEnabled(!be.isVoidModeEnabled());
                    sendSyncToClient(player, be);
                }
                case 2 -> { // 设置箱子ID
                    String newId = packet.value.trim();
                    if (!newId.isEmpty() && be.getOwnerUUID() != null) {
                        ChestRegistryManager.getInstance().updateChestId(
                            be.getOwnerUUID(), be.getChestId(), newId,
                            be.getLevel(), be.getBlockPos(), be.getChestType());
                        be.setChestId(newId);
                    }
                    sendSyncToClient(player, be);
                }
                case 3 -> { // 设置绑定目标
                    be.setBoundTargetId(packet.value.trim());
                    sendSyncToClient(player, be);
                }
                case 4 -> { // 循环绑定目标
                    cycleBindingTarget(player, be);
                }
                case 5 -> { // 增加速率限制
                    int newRate = Math.min(be.getTransferRateLimit() + 10, 10000);
                    be.setTransferRateLimit(newRate);
                    sendSyncToClient(player, be);
                }
                case 6 -> { // 减少速率限制
                    int newRate = Math.max(be.getTransferRateLimit() - 10, 0);
                    be.setTransferRateLimit(newRate);
                    sendSyncToClient(player, be);
                }
                case 7 -> { // 滚动存货区
                    try {
                        int newRow = Integer.parseInt(packet.value);
                        int maxRow = Math.max(0, (be.getAllItems().size() + BaseChestMenu.CHEST_COLS - 1) / BaseChestMenu.CHEST_COLS - BaseChestMenu.CHEST_VISIBLE_ROWS);
                        be.viewScrollRow = Math.max(0, Math.min(maxRow, newRow));
                        menu.chestContainer.refill(be.viewScrollRow);
                        be.setChanged();
                        // 强制触发 broadcastChanges
                        be.storageDirty = true;
                    } catch (NumberFormatException ignored) {}
                }
            }
        });
    }

    /**
     * 循环切换到下一个可绑定的目标ID
     */
    private static void cycleBindingTarget(ServerPlayer player, BaseChestBlockEntity be) {
        if (be.getOwnerUUID() == null) return;
        if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        ChestRegistryManager.ChestType targetType = be.getChestType() == ChestRegistryManager.ChestType.SUPPLY
            ? ChestRegistryManager.ChestType.PICKUP
            : ChestRegistryManager.ChestType.SUPPLY;

        java.util.Set<String> available = ChestRegistryManager.getInstance()
            .getUnboundChestIds(be.getOwnerUUID(), targetType, serverLevel);

        // 也包含当前已绑定的目标（防止绑定了就消失）
        if (!be.getBoundTargetId().isEmpty()) {
            available.add(be.getBoundTargetId());
        }

        // 去掉自己的ID
        available.remove(be.getChestId());

        if (available.isEmpty()) {
            be.setBoundTargetId("");
            sendSyncToClient(player, be);
            return;
        }

        java.util.List<String> sorted = new java.util.ArrayList<>(available);
        java.util.Collections.sort(sorted);

        // 找到当前绑定在列表中的位置，切换到下一个
        int idx = sorted.indexOf(be.getBoundTargetId());
        int nextIdx = (idx + 1) % sorted.size();
        be.setBoundTargetId(sorted.get(nextIdx));
        sendSyncToClient(player, be);
    }

    /**
     * 向客户端发送同步数据包（同时供 BlockEntity.createMenu 调用）
     */
    public static void sendSyncToClient(ServerPlayer player, BaseChestBlockEntity be) {
        if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        ChestRegistryManager.ChestType targetType = be.getChestType() == ChestRegistryManager.ChestType.SUPPLY
            ? ChestRegistryManager.ChestType.PICKUP
            : ChestRegistryManager.ChestType.SUPPLY;

        java.util.Set<String> available = ChestRegistryManager.getInstance()
            .getUnboundChestIds(be.getOwnerUUID(), targetType, serverLevel);
        if (!be.getBoundTargetId().isEmpty()) {
            available.add(be.getBoundTargetId());
        }
        available.remove(be.getChestId());

        java.util.List<String> bindings = new java.util.ArrayList<>(available);
        java.util.Collections.sort(bindings);

        ChestSyncPacket sync = new ChestSyncPacket(
            be.getChestId(),
            be.getBoundTargetId(),
            be.isTransferEnabled(),
            be.isVoidModeEnabled(),
            be.getTransferRateLimit(),
            bindings
        );
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, sync);
    }
}
