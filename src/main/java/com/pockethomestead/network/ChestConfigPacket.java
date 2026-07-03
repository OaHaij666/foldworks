package com.pockethomestead.network;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.RelativeSide;
import com.pockethomestead.blockentity.ResourceKind;
import com.pockethomestead.blockentity.SideMode;
import com.pockethomestead.menu.BaseChestMenu;
import com.pockethomestead.permission.AccessControl;
import com.pockethomestead.production.ProductionStatsStorage;
import com.pockethomestead.item.HomesteadTabletBinding;
import com.pockethomestead.registration.ModItems;
import com.pockethomestead.registry.ChestRegistryManager;
import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferGraphStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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

public record ChestConfigPacket(int action, String value, ItemStack stack) implements CustomPacketPayload {
    public static final Type<ChestConfigPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("pockethomestead", "chest_config"));

    public ChestConfigPacket(int action, String value) {
        this(action, value, ItemStack.EMPTY);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestConfigPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChestConfigPacket decode(RegistryFriendlyByteBuf buf) {
            int action = ByteBufCodecs.VAR_INT.decode(buf);
            String value = ByteBufCodecs.STRING_UTF8.decode(buf);
            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            return new ChestConfigPacket(action, value, stack);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ChestConfigPacket pkt) {
            ByteBufCodecs.VAR_INT.encode(buf, pkt.action);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.value);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, pkt.stack == null ? ItemStack.EMPTY : pkt.stack.copyWithCount(1));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ChestConfigPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.containerMenu instanceof BaseChestMenu menu)) return;
            BaseChestBlockEntity be = menu.getBlockEntity();
            if (be == null) return;
            SpacePermission.AccessLevel required = requiredLevel(packet.action);
            if (!AccessControl.canChest(player.getUUID(), be, required)) {
                AccessControl.deny(player);
                return;
            }

            switch (packet.action) {
                case 0 -> { be.setTransferEnabled(!be.isTransferEnabled()); sendSyncToClient(player, be); }
                case 1 -> { be.setVoidModeEnabled(!be.isVoidModeEnabled()); sendSyncToClient(player, be); }
                case 2 -> {
                    String newId = packet.value.trim();
                    if (!newId.isEmpty() && be.getOwnerUUID() != null) {
                        String oldId = be.getChestId();
                        ChestRegistryManager.getInstance().updateChestId(
                            be.getOwnerUUID(), oldId, newId,
                            be.getLevel(), be.getBlockPos());
                        be.setChestId(newId);
                        if (player.server != null && be.getLevel() != null) {
                            TransferGraphStorage graphStorage = TransferGraphStorage.get(player.server);
                            graphStorage.updateChestId(oldId, newId, be.getLevel().dimension().location().toString(), be.getBlockPos());
                            refreshBoundTabletsAfterRename(player, be, oldId, newId);
                        }
                    }
                    sendSyncToClient(player, be);
                }
                case 5 -> { be.setTransferRateLimit(Math.min(be.getTransferRateLimit() + 10, 10000)); sendSyncToClient(player, be); }
                case 6 -> { be.setTransferRateLimit(Math.max(be.getTransferRateLimit() - 10, 0)); sendSyncToClient(player, be); }
                case 7 -> { try { be.setViewScrollRow(Integer.parseInt(packet.value)); } catch (NumberFormatException ignored) {} }
                case 8 -> putFromCarried(menu, be, false);
                case 12 -> putFromCarried(menu, be, true);
                case 9 -> takeToCarried(menu, be, packet.value, packet.stack, false);
                case 10 -> takeToCarried(menu, be, packet.value, packet.stack, true);
                case 11 -> shiftTakeToInventory(player, menu, be, packet.value, packet.stack);
                case 14 -> putUpgradeFromCarried(menu, be, packet.value, false);
                case 15 -> putUpgradeFromCarried(menu, be, packet.value, true);
                case 16 -> takeUpgradeToCarried(player, menu, be, packet.value, false);
                case 17 -> takeUpgradeToCarried(player, menu, be, packet.value, true);
                case 18 -> applySideFunction(be, packet.value);
                case 19 -> applyStressOutputSpeed(be, packet.value);
                case 20 -> applyStressOutputDirection(be, packet.value);
                case 21 -> applyGraphAccess(be, packet.value);
                case 22 -> { be.setOfflineSnapshotEnabled(!be.isOfflineSnapshotEnabled()); sendSyncToClient(player, be); }
                default -> {}
            }

            if (packet.action >= 8) {
                menu.broadcastChanges();
                sendSyncToClient(player, be);
            }
        });
    }

    private static SpacePermission.AccessLevel requiredLevel(int action) {
        return switch (action) {
            case 0, 1, 2, 5, 6, 14, 15, 16, 17, 18, 19, 20, 22 -> SpacePermission.AccessLevel.WRITE;
            case 21 -> SpacePermission.AccessLevel.MANAGE;
            case 7, 8, 9, 10, 11, 12 -> SpacePermission.AccessLevel.USE;
            default -> SpacePermission.AccessLevel.MANAGE;
        };
    }

    private static void refreshBoundTabletsAfterRename(ServerPlayer actor, BaseChestBlockEntity be, String oldId, String newId) {
        if (actor.server == null || be.getOwnerUUID() == null || be.getLevel() == null) return;
        String dim = be.getLevel().dimension().location().toString();
        for (ServerPlayer online : actor.server.getPlayerList().getPlayers()) {
            boolean changed = false;
            for (int i = 0; i < online.getInventory().items.size(); i++) {
                changed |= refreshTabletStack(online.getInventory().items.get(i), be, oldId, dim, newId);
            }
            for (int i = 0; i < online.getInventory().offhand.size(); i++) {
                changed |= refreshTabletStack(online.getInventory().offhand.get(i), be, oldId, dim, newId);
            }
            if (changed) online.getInventory().setChanged();
        }
    }

    private static boolean refreshTabletStack(ItemStack stack, BaseChestBlockEntity be, String oldId, String dim, String newId) {
        if (stack == null || stack.isEmpty() || !stack.is(ModItems.HOMESTEAD_TABLET.get())) return false;
        return HomesteadTabletBinding.rewriteChestIdIfMatches(stack, be.getOwnerUUID(), oldId, dim, be.getBlockPos(), newId);
    }

    private static void putFromCarried(BaseChestMenu menu, BaseChestBlockEntity be, boolean onlyOne) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) return;
        int want = Math.min(onlyOne ? 1 : carried.getCount(), carried.getCount());
        int added = be.addItem(carried, want);
        if (added > 0) {
            carried.shrink(added);
            menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }
    }

    private static void takeToCarried(BaseChestMenu menu, BaseChestBlockEntity be, String itemId, ItemStack prototype, boolean onlyOne) {
        Item item = prototype != null && !prototype.isEmpty() ? prototype.getItem() : resolveItem(itemId);
        if (item == null) return;

        int have = prototype != null && !prototype.isEmpty() ? be.getStoredItems().stream()
                .filter(stored -> stored.matches(prototype)).mapToInt(com.pockethomestead.blockentity.StoredItemStack::count).findFirst().orElse(0)
                : be.getItemCount(item);
        if (have <= 0) return;

        ItemStack carried = menu.getCarried();
        ItemStack baseStack = prototype != null && !prototype.isEmpty() ? prototype.copyWithCount(1) : new ItemStack(item);
        int maxStack = baseStack.getMaxStackSize();
        int want;
        if (carried.isEmpty()) want = onlyOne ? 1 : maxStack;
        else if (ItemStack.isSameItemSameComponents(carried, baseStack)) want = onlyOne ? 1 : (maxStack - carried.getCount());
        else return;
        want = Math.min(want, have);
        if (want <= 0) return;

        int removed = prototype != null && !prototype.isEmpty() ? be.removeItem(baseStack, want) : be.removeItem(item, want);
        if (removed > 0) {
            if (carried.isEmpty()) menu.setCarried(baseStack.copyWithCount(removed));
            else {
                carried.grow(removed);
                menu.setCarried(carried);
            }
        }
    }

    private static void shiftTakeToInventory(ServerPlayer player, BaseChestMenu menu, BaseChestBlockEntity be, String itemId, ItemStack prototype) {
        Item item = prototype != null && !prototype.isEmpty() ? prototype.getItem() : resolveItem(itemId);
        if (item == null) return;

        int have = prototype != null && !prototype.isEmpty() ? be.getStoredItems().stream()
                .filter(stored -> stored.matches(prototype)).mapToInt(com.pockethomestead.blockentity.StoredItemStack::count).findFirst().orElse(0)
                : be.getItemCount(item);
        if (have <= 0) return;

        ItemStack baseStack = prototype != null && !prototype.isEmpty() ? prototype.copyWithCount(1) : new ItemStack(item);
        int want = Math.min(baseStack.getMaxStackSize(), have);
        ItemStack toGive = baseStack.copyWithCount(want);
        player.getInventory().add(toGive);
        int actuallyAdded = want - toGive.getCount();
        if (actuallyAdded > 0) {
            if (prototype != null && !prototype.isEmpty()) be.removeItem(baseStack, actuallyAdded);
            else be.removeItem(item, actuallyAdded);
        }
    }

    private static Item resolveItem(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return null;
        Item item = BuiltInRegistries.ITEM.get(loc);
        return item == Items.AIR ? null : item;
    }

    private static void putUpgradeFromCarried(BaseChestMenu menu, BaseChestBlockEntity be, String slotValue, boolean onlyOne) {
        int slot = parseSlot(slotValue);
        ItemStack carried = menu.getCarried();
        if (!be.canPlaceUpgrade(slot, carried)) return;
        int added = be.addUpgrade(slot, carried, onlyOne ? 1 : carried.getCount());
        if (added > 0) {
            carried.shrink(added);
            menu.setCarried(carried.isEmpty() ? ItemStack.EMPTY : carried);
        }
    }

    private static void takeUpgradeToCarried(ServerPlayer player, BaseChestMenu menu, BaseChestBlockEntity be, String slotValue, boolean onlyOne) {
        int slot = parseSlot(slotValue);
        Item expected = be.getUpgradeItem(slot);
        if (expected == null || be.getUpgradeCount(slot) <= 0) return;
        ItemStack carried = menu.getCarried();
        ItemStack base = new ItemStack(expected);
        if (!carried.isEmpty() && !ItemStack.isSameItemSameComponents(carried, base)) return;
        int room = carried.isEmpty() ? base.getMaxStackSize() : base.getMaxStackSize() - carried.getCount();
        int want = onlyOne ? 1 : room;
        if (want <= 0) return;
        if (!be.canRemoveUpgrade(slot, want)) {
            player.displayClientMessage(Component.literal(upgradeBlockedMessage(slot)), true);
            return;
        }
        ItemStack removed = be.removeUpgrade(slot, want);
        if (removed.isEmpty()) return;
        if (carried.isEmpty()) menu.setCarried(removed);
        else {
            carried.grow(removed.getCount());
            menu.setCarried(carried);
        }
    }

    private static String upgradeBlockedMessage(int slot) {
        return switch (slot) {
            case BaseChestBlockEntity.STORAGE_UPGRADE_SLOT -> "物品容量不足，不能取下存储升级";
            case BaseChestBlockEntity.FLUID_UPGRADE_SLOT -> "流体容量不足，不能取下流体升级";
            case BaseChestBlockEntity.ENERGY_UPGRADE_SLOT -> "电力容量不足，不能取下电力升级";
            default -> "当前不能取下此升级";
        };
    }

    private static void applySideFunction(BaseChestBlockEntity be, String value) {
        String[] parts = value.split("\\|");
        if (parts.length != 3) return;
        try {
            be.setSideFunction(RelativeSide.valueOf(parts[0]), ResourceKind.valueOf(parts[1]), SideMode.valueOf(parts[2]));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void applyStressOutputSpeed(BaseChestBlockEntity be, String value) {
        try {
            be.setStressOutputSpeedRpm(Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
        }
    }

    private static void applyStressOutputDirection(BaseChestBlockEntity be, String value) {
        be.setStressOutputReversed("1".equals(value) || "true".equalsIgnoreCase(value));
    }

    private static void applyGraphAccess(BaseChestBlockEntity be, String value) {
        String[] parts = value == null ? new String[0] : value.split("\\|", -1);
        GraphKey.Kind kind;
        try {
            kind = parts.length > 0 ? GraphKey.Kind.valueOf(parts[0]) : GraphKey.Kind.PRIVATE;
        } catch (IllegalArgumentException e) {
            kind = GraphKey.Kind.PRIVATE;
        }
        java.util.UUID teamId = null;
        if (kind == GraphKey.Kind.PROTECTED && parts.length > 1 && !parts[1].isBlank()) {
            try {
                teamId = java.util.UUID.fromString(parts[1]);
            } catch (IllegalArgumentException ignored) {
            }
        }
        be.setGraphAccess(kind, teamId);
        if (be.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.world.level.block.state.BlockState state = be.getLevel().getBlockState(be.getBlockPos());
            be.getLevel().sendBlockUpdated(be.getBlockPos(), state, state, 3);
            serverLevel.getChunkSource().blockChanged(be.getBlockPos());
        }
    }

    private static int parseSlot(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return -1; }
    }

    public static void sendSyncToClient(ServerPlayer player, BaseChestBlockEntity be) {
        if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        java.util.List<ChestSyncPacket.ItemEntry> items = new java.util.ArrayList<>();
        for (com.pockethomestead.blockentity.StoredItemStack e : be.getStoredItems()) {
            items.add(new ChestSyncPacket.ItemEntry(e.prototype(), e.count()));
        }

        Map<String, Integer> fluids = new LinkedHashMap<>();
        for (Map.Entry<net.minecraft.world.level.material.Fluid, Integer> e : be.getAllFluids().entrySet()) {
            ResourceLocation key = BuiltInRegistries.FLUID.getKey(e.getKey());
            fluids.put(key.toString(), e.getValue());
        }

        ProductionStatsStorage productionStats = ProductionStatsStorage.get(serverLevel.getServer());
        String productionKey = be.productionChestKey();
        boolean productionStatsEnabled = be.getOwnerUUID() != null && productionStats.isChestEnabled(be.getOwnerUUID(), productionKey);
        String productionGroupId = be.getOwnerUUID() == null ? "" : productionStats.chestGroup(be.getOwnerUUID(), productionKey);
        com.pockethomestead.space.SpaceData space = com.pockethomestead.space.SpaceManager.getInstance()
                .getSpaceByDimension(be.getLevel().dimension().location());
        boolean spaceOfflineSimulationEnabled = space != null && space.isOfflineSimulationEnabled();

        java.util.List<ChestSyncPacket.SideConfigEntry> sideEntries = new java.util.ArrayList<>();
        for (ResourceKind kind : ResourceKind.values()) {
            for (RelativeSide side : RelativeSide.values()) {
                sideEntries.add(new ChestSyncPacket.SideConfigEntry(kind.name(), side.name(), be.getSideMode(kind, side).name()));
            }
        }
        GraphKey graphKey = be.getGraphKey();
        String graphId = graphKey != null
                && graphKey.id() != null
                && (graphKey.kind() == GraphKey.Kind.PROTECTED || graphKey.kind() == GraphKey.Kind.SPACE)
                ? graphKey.id().toString()
                : "";

        ChestSyncPacket sync = new ChestSyncPacket(
            be.getChestId(),
            be.getBlockPos(),
            be.isTransferEnabled(),
            be.isVoidModeEnabled(),
            be.isOfflineSnapshotEnabled(),
            spaceOfflineSimulationEnabled,
            be.getTransferRateLimit(),
            be.getNextTransferSeconds(),
            be.getMaxItemCapacity(),
            be.getMaxFluidCapacityMb(),
            be.getMaxFluidTypes(),
            be.getMaxFluidCapacityPerTypeMb(),
            be.getEnergyStored(),
            be.getMaxEnergyStored(),
            be.getEnergyTransferLimit(),
            be.getNetworkBandwidthCapacity(),
            be.getStressBandwidthUsed(),
            be.getRemainingTransferBandwidth(),
            be.hasStressUpgrade(),
            net.neoforged.fml.ModList.get().isLoaded("create"),
            be.getStressOutputSpeedRpm(),
            be.isStressOutputReversed(),
            be.getGraphKind().name(),
            graphId,
            productionStatsEnabled,
            productionGroupId,
            java.util.Arrays.stream(be.getUpgradeCounts()).boxed().toList(),
            sideEntries,
            items,
            fluids
        );
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, sync);
    }
}
