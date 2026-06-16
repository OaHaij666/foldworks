package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.registry.ChestRegistryManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端→服务端：更新箱子配置 / 存取物品
 * action:
 *   0=切换传送, 1=切换虚空模式, 2=设置ID, 3=双向绑定/解绑(value=目标id或空), 4=循环绑定,
 *   5=增加速率, 6=减少速率, 7=滚动(仅服务端记录),
 *   8=放入手持全部, 9=取出一组到手持, 10=取出一个到手持, 11=Shift取出到背包, 12=放入手持一个,
 *   13=设置绑定对同步间隔(value=秒数)
 * value: 取/放动作时为物品注册名（如 minecraft:oak_log）；绑定动作时为目标箱子ID或空串
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

    public static void handle(ChestConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.containerMenu instanceof BaseChestMenu menu)) return;

            BaseChestBlockEntity be = menu.getBlockEntity();
            if (be == null) return;

            switch (packet.action) {
                case 0 -> { be.setTransferEnabled(!be.isTransferEnabled()); sendSyncToClient(player, be); }
                case 1 -> { be.setVoidModeEnabled(!be.isVoidModeEnabled()); sendSyncToClient(player, be); }
                case 2 -> {
                    String newId = packet.value.trim();
                    if (!newId.isEmpty() && be.getOwnerUUID() != null) {
                        ChestRegistryManager.getInstance().updateChestId(
                            be.getOwnerUUID(), be.getChestId(), newId,
                            be.getLevel(), be.getBlockPos(), be.getChestType());
                        be.setChestId(newId);
                    }
                    sendSyncToClient(player, be);
                }
                case 3 -> applyBinding(player, be, packet.value.trim()); // 双向原子绑定/解绑
                case 4 -> cycleBindingTarget(player, be);
                case 5 -> { be.setTransferRateLimit(Math.min(be.getTransferRateLimit() + 10, 10000)); sendSyncToClient(player, be); }
                case 6 -> { be.setTransferRateLimit(Math.max(be.getTransferRateLimit() - 10, 0)); sendSyncToClient(player, be); }
                case 7 -> { try { be.viewScrollRow = Math.max(0, Integer.parseInt(packet.value)); } catch (NumberFormatException ignored) {} }
                case 13 -> applySyncInterval(player, be, packet.value.trim()); // 设置绑定对的同步间隔

                case 8 -> putFromCarried(menu, be, false);     // 放入全部
                case 12 -> putFromCarried(menu, be, true);     // 放入一个
                case 9 -> takeToCarried(menu, be, packet.value, false);  // 取一组
                case 10 -> takeToCarried(menu, be, packet.value, true);  // 取一个
                case 11 -> shiftTakeToInventory(player, menu, be, packet.value);  // Shift取出

                default -> {}
            }

            // 存取动作后立即同步
            if (packet.action >= 8) {
                menu.broadcastChanges();
                sendSyncToClient(player, be);
            }
        });
    }

    // ===== 存取逻辑（服务端唯一权威） =====

    /** 放入手持物品到箱子 */
    private static void putFromCarried(BaseChestMenu menu, BaseChestBlockEntity be, boolean onlyOne) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return;

        int want = onlyOne ? 1 : carried.getCount();
        want = Math.min(want, carried.getCount());
        int added = be.addItem(carried.getItem(), want);
        if (added > 0) {
            carried.shrink(added);
            menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }
    }

    /** 从箱子取物品到手持 */
    private static void takeToCarried(BaseChestMenu menu, BaseChestBlockEntity be, String itemId, boolean onlyOne) {
        Item item = resolveItem(itemId);
        if (item == null) return;

        int have = be.getItemCount(item);
        if (have <= 0) return;

        ItemStack carried = menu.getCarried();
        int maxStack = new ItemStack(item).getMaxStackSize();

        int want;
        if (carried.isEmpty()) {
            want = onlyOne ? 1 : maxStack;
        } else if (carried.getItem() == item) {
            want = onlyOne ? 1 : (maxStack - carried.getCount());
        } else {
            return; // 手持其他物品，无法取
        }
        want = Math.min(want, have);
        if (want <= 0) return;

        int removed = be.removeItem(item, want);
        if (removed > 0) {
            if (carried.isEmpty()) {
                menu.setCarried(new ItemStack(item, removed));
            } else {
                carried.grow(removed);
                menu.setCarried(carried);
            }
        }
    }

    /** Shift取出一组到玩家背包 */
    private static void shiftTakeToInventory(ServerPlayer player, BaseChestMenu menu, BaseChestBlockEntity be, String itemId) {
        Item item = resolveItem(itemId);
        if (item == null) return;

        int have = be.getItemCount(item);
        if (have <= 0) return;

        int maxStack = new ItemStack(item).getMaxStackSize();
        int want = Math.min(maxStack, have);

        ItemStack toGive = new ItemStack(item, want);
        player.getInventory().add(toGive);
        int leftover = toGive.getCount();
        int actuallyAdded = want - leftover;
        if (actuallyAdded > 0) {
            be.removeItem(item, actuallyAdded);
        }
    }

    private static Item resolveItem(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return null;
        Item item = BuiltInRegistries.ITEM.get(loc);
        return (item == Items.AIR) ? null : item;
    }

    // ===== 绑定（双向原子）=====

    private static ChestRegistryManager.ChestType oppositeType(ChestRegistryManager.ChestType t) {
        return t == ChestRegistryManager.ChestType.SUPPLY
            ? ChestRegistryManager.ChestType.PICKUP
            : ChestRegistryManager.ChestType.SUPPLY;
    }

    /** 查找 be 当前绑定的对端箱子（按 be.boundTargetId + 对立类型） */
    private static BaseChestBlockEntity findPartner(BaseChestBlockEntity be) {
        if (be.getBoundTargetId().isEmpty() || be.getOwnerUUID() == null) return null;
        if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)) return null;
        return ChestRegistryManager.getInstance()
            .findBoundChest(be.getOwnerUUID(), be.getBoundTargetId(), oppositeType(be.getChestType()), sl);
    }

    /**
     * 双向原子绑定/解绑。
     * value 为空 → 解绑（清空双方）；
     * value 为目标ID → 绑定：先清理各自旧伙伴，再互相指向，同步间隔取 be 当前值。
     */
    private static void applyBinding(ServerPlayer player, BaseChestBlockEntity be, String targetId) {
        if (targetId.isEmpty()) {
            // 解绑
            BaseChestBlockEntity partner = findPartner(be);
            be.setBoundTargetId("");
            if (partner != null) partner.setBoundTargetId("");
            sendSyncToClient(player, be);
            syncToViewers(player.server, partner);
            return;
        }

        if (be.getOwnerUUID() == null) return;
        if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)) return;

        BaseChestBlockEntity target = ChestRegistryManager.getInstance()
            .findBoundChest(be.getOwnerUUID(), targetId, oppositeType(be.getChestType()), sl);
        if (target == null || target == be) {
            // 目标不存在或自绑：保持原状，仅同步
            sendSyncToClient(player, be);
            return;
        }

        // 清理 be 旧伙伴（若与新目标不同）
        BaseChestBlockEntity oldPartnerOfBe = findPartner(be);
        if (oldPartnerOfBe != null && oldPartnerOfBe != target) {
            oldPartnerOfBe.setBoundTargetId("");
            syncToViewers(player.server, oldPartnerOfBe);
        }
        // 清理 target 旧伙伴（若与 be 不同）
        BaseChestBlockEntity oldPartnerOfTarget = findPartner(target);
        if (oldPartnerOfTarget != null && oldPartnerOfTarget != be) {
            oldPartnerOfTarget.setBoundTargetId("");
            syncToViewers(player.server, oldPartnerOfTarget);
        }

        // 互相指向
        be.setBoundTargetId(target.getChestId());
        target.setBoundTargetId(be.getChestId());
        // 同步间隔以 be 当前值为准
        target.setSyncIntervalSeconds(be.getSyncIntervalSeconds());

        sendSyncToClient(player, be);
        syncToViewers(player.server, target);
    }

    /** 设置绑定对的同步间隔（双方同步） */
    private static void applySyncInterval(ServerPlayer player, BaseChestBlockEntity be, String value) {
        int seconds;
        try { seconds = Integer.parseInt(value); } catch (NumberFormatException e) { return; }
        seconds = Math.max(1, Math.min(3600, seconds));
        be.setSyncIntervalSeconds(seconds);
        BaseChestBlockEntity partner = findPartner(be);
        if (partner != null) partner.setSyncIntervalSeconds(seconds);
        sendSyncToClient(player, be);
        syncToViewers(player.server, partner);
    }

    /** 给所有正在查看某箱子的玩家推送同步包（保证对端 UI 实时刷新） */
    private static void syncToViewers(net.minecraft.server.MinecraftServer server, BaseChestBlockEntity be) {
        if (be == null || server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof BaseChestMenu m && m.getBlockEntity() == be) {
                sendSyncToClient(p, be);
            }
        }
    }

    private static void cycleBindingTarget(ServerPlayer player, BaseChestBlockEntity be) {
        if (be.getOwnerUUID() == null) return;
        if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        ChestRegistryManager.ChestType targetType = oppositeType(be.getChestType());

        java.util.Set<String> available = ChestRegistryManager.getInstance()
            .getAllChestIdsOfType(be.getOwnerUUID(), targetType);
        available.remove(be.getChestId());

        if (available.isEmpty()) {
            applyBinding(player, be, "");
            return;
        }

        java.util.List<String> sorted = new java.util.ArrayList<>(available);
        java.util.Collections.sort(sorted);
        int idx = sorted.indexOf(be.getBoundTargetId());
        int nextIdx = (idx + 1) % sorted.size();
        applyBinding(player, be, sorted.get(nextIdx));
    }

    // ===== 同步到客户端 =====

    public static void sendSyncToClient(ServerPlayer player, BaseChestBlockEntity be) {
        if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        ChestRegistryManager.ChestType targetType = oppositeType(be.getChestType());

        // 获取所有对端箱子ID（不依赖区块加载），保证下拉栏始终有可选项
        java.util.Set<String> available = ChestRegistryManager.getInstance()
            .getAllChestIdsOfType(be.getOwnerUUID(), targetType);
        available.remove(be.getChestId());

        java.util.List<String> bindings = new java.util.ArrayList<>(available);
        java.util.Collections.sort(bindings);

        // 构建物品快照（itemId → count）
        Map<String, Integer> items = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : be.getAllItems().entrySet()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(e.getKey());
            items.put(key.toString(), e.getValue());
        }

        ChestSyncPacket sync = new ChestSyncPacket(
            be.getChestId(),
            be.getBoundTargetId(),
            be.isTransferEnabled(),
            be.isVoidModeEnabled(),
            be.getTransferRateLimit(),
            be.getSyncIntervalSeconds(),
            be.getNextTransferSeconds(),
            com.pockethomestead.config.ModConfig.MAX_CHEST_CAPACITY.get(),
            bindings,
            items
        );
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, sync);
    }
}
