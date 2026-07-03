package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.StoredItemStack;
import com.pockethomestead.item.HomesteadTabletBinding;
import com.pockethomestead.permission.AccessControl;
import com.pockethomestead.space.SpacePermission;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record TabletChestActionPacket(int action, int playerSlot, ItemStack stack) implements CustomPacketPayload {
    public static final int REQUEST = 0;
    public static final int CLICK_PLAYER_LEFT = 1;
    public static final int CLICK_PLAYER_RIGHT = 2;
    public static final int CLICK_CHEST_LEFT = 3;
    public static final int CLICK_CHEST_RIGHT = 4;
    public static final int QUICK_MOVE_PLAYER = 5;
    public static final int QUICK_MOVE_CHEST = 6;
    public static final int RETURN_CARRIED = 7;

    private static final Map<UUID, ItemStack> CARRIED = new ConcurrentHashMap<>();

    public static final Type<TabletChestActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "tablet_chest_action"));

    public TabletChestActionPacket(int action) {
        this(action, -1, ItemStack.EMPTY);
    }

    public TabletChestActionPacket(int action, int playerSlot) {
        this(action, playerSlot, ItemStack.EMPTY);
    }

    public TabletChestActionPacket(int action, ItemStack stack) {
        this(action, -1, stack);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, TabletChestActionPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TabletChestActionPacket decode(RegistryFriendlyByteBuf buf) {
            int action = ByteBufCodecs.VAR_INT.decode(buf);
            int playerSlot = ByteBufCodecs.VAR_INT.decode(buf);
            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            return new TabletChestActionPacket(action, playerSlot, stack);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, TabletChestActionPacket packet) {
            ByteBufCodecs.VAR_INT.encode(buf, packet.action);
            ByteBufCodecs.VAR_INT.encode(buf, packet.playerSlot);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.stack == null ? ItemStack.EMPTY : packet.stack.copyWithCount(1));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TabletChestActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            ItemStack tablet = HomesteadTabletBinding.findHeldTablet(player);
            if (tablet.isEmpty()) {
                returnCarried(player);
                sendUnavailable(player, false, "请手持尘歌玉盘");
                return;
            }
            if (HomesteadTabletBinding.read(tablet).isEmpty()) {
                returnCarried(player);
                sendUnavailable(player, false, "潜行右键传输箱以绑定");
                return;
            }

            BaseChestBlockEntity chest = HomesteadTabletBinding.resolve(player, tablet);
            if (chest == null) {
                returnCarried(player);
                sendUnavailable(player, true, "绑定的箱子未加载或已不存在");
                return;
            }
            HomesteadTabletBinding.refreshLocation(tablet, chest);

            if (!AccessControl.canChest(player.getUUID(), chest, SpacePermission.AccessLevel.USE)) {
                AccessControl.deny(player);
                send(player, chest);
                return;
            }

            switch (packet.action) {
                case CLICK_PLAYER_LEFT -> clickPlayer(player, packet.playerSlot, false);
                case CLICK_PLAYER_RIGHT -> clickPlayer(player, packet.playerSlot, true);
                case CLICK_CHEST_LEFT -> clickChest(player, chest, packet.stack, false);
                case CLICK_CHEST_RIGHT -> clickChest(player, chest, packet.stack, true);
                case QUICK_MOVE_PLAYER -> quickMovePlayerToChest(player, chest, packet.playerSlot);
                case QUICK_MOVE_CHEST -> quickMoveChestToPlayer(player, chest, packet.stack);
                case RETURN_CARRIED -> returnCarried(player);
                default -> {
                }
            }
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            send(player, chest);
        });
    }

    private static void clickPlayer(ServerPlayer player, int slot, boolean rightClick) {
        if (slot < 0 || slot >= player.getInventory().items.size()) return;
        ItemStack slotStack = player.getInventory().items.get(slot);
        ItemStack carried = carried(player);
        if (rightClick) {
            rightClickPlayerSlot(player, slot, slotStack, carried);
        } else {
            leftClickPlayerSlot(player, slot, slotStack, carried);
        }
    }

    private static void leftClickPlayerSlot(ServerPlayer player, int slot, ItemStack slotStack, ItemStack carried) {
        if (carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            setCarried(player, slotStack.copy());
            player.getInventory().items.set(slot, ItemStack.EMPTY);
            return;
        }

        if (slotStack.isEmpty()) {
            player.getInventory().items.set(slot, carried.copy());
            clearCarried(player);
            return;
        }

        if (ItemStack.isSameItemSameComponents(slotStack, carried)) {
            int moved = Math.min(carried.getCount(), Math.max(0, slotStack.getMaxStackSize() - slotStack.getCount()));
            if (moved > 0) {
                slotStack.grow(moved);
                carried.shrink(moved);
                if (carried.isEmpty()) clearCarried(player);
            }
            return;
        }

        player.getInventory().items.set(slot, carried.copy());
        setCarried(player, slotStack.copy());
    }

    private static void rightClickPlayerSlot(ServerPlayer player, int slot, ItemStack slotStack, ItemStack carried) {
        if (carried.isEmpty()) {
            if (slotStack.isEmpty()) return;
            int taken = (slotStack.getCount() + 1) / 2;
            ItemStack picked = slotStack.copyWithCount(taken);
            slotStack.shrink(taken);
            if (slotStack.isEmpty()) player.getInventory().items.set(slot, ItemStack.EMPTY);
            setCarried(player, picked);
            return;
        }

        if (slotStack.isEmpty()) {
            player.getInventory().items.set(slot, carried.copyWithCount(1));
            carried.shrink(1);
            if (carried.isEmpty()) clearCarried(player);
            return;
        }

        if (ItemStack.isSameItemSameComponents(slotStack, carried) && slotStack.getCount() < slotStack.getMaxStackSize()) {
            slotStack.grow(1);
            carried.shrink(1);
            if (carried.isEmpty()) clearCarried(player);
        }
    }

    private static void clickChest(ServerPlayer player, BaseChestBlockEntity chest, ItemStack prototype, boolean rightClick) {
        ItemStack carried = carried(player);
        if (carried.isEmpty()) {
            if (prototype == null || prototype.isEmpty()) return;
            int stored = storedCount(chest, prototype);
            if (stored <= 0) return;
            int slotStackSize = Math.min(prototype.getMaxStackSize(), stored);
            int amount = rightClick ? (slotStackSize + 1) / 2 : slotStackSize;
            int removed = chest.removeItem(prototype.copyWithCount(1), amount);
            if (removed > 0) setCarried(player, prototype.copyWithCount(removed));
            return;
        }

        if (!rightClick && prototype != null && !prototype.isEmpty()
                && !ItemStack.isSameItemSameComponents(carried, prototype)) {
            int deposited = chest.addItem(carried, carried.getCount());
            carried.shrink(deposited);
            if (carried.isEmpty()) {
                clearCarried(player);
                int stored = storedCount(chest, prototype);
                int removed = chest.removeItem(prototype.copyWithCount(1), Math.min(prototype.getMaxStackSize(), stored));
                if (removed > 0) setCarried(player, prototype.copyWithCount(removed));
            }
            return;
        }

        int amount = rightClick ? 1 : carried.getCount();
        int added = chest.addItem(carried, amount);
        carried.shrink(added);
        if (carried.isEmpty()) clearCarried(player);
    }

    private static void quickMovePlayerToChest(ServerPlayer player, BaseChestBlockEntity chest, int slot) {
        if (slot < 0 || slot >= player.getInventory().items.size()) return;
        ItemStack source = player.getInventory().items.get(slot);
        if (source.isEmpty()) return;
        int added = chest.addItem(source, source.getCount());
        if (added <= 0) return;
        source.shrink(added);
        if (source.isEmpty()) player.getInventory().items.set(slot, ItemStack.EMPTY);
    }

    private static void quickMoveChestToPlayer(ServerPlayer player, BaseChestBlockEntity chest, ItemStack prototype) {
        if (prototype == null || prototype.isEmpty()) return;
        int stored = storedCount(chest, prototype);
        if (stored <= 0) return;
        int amount = Math.min(prototype.getMaxStackSize(), stored);
        int removed = chest.removeItem(prototype.copyWithCount(1), amount);
        if (removed <= 0) return;

        ItemStack moving = prototype.copyWithCount(removed);
        player.getInventory().add(moving);
        int inserted = removed - moving.getCount();
        if (moving.getCount() > 0) chest.addItem(prototype.copyWithCount(1), moving.getCount());
    }

    private static int storedCount(BaseChestBlockEntity chest, ItemStack prototype) {
        return chest.getStoredItems().stream()
                .filter(entry -> entry.matches(prototype))
                .mapToInt(StoredItemStack::count)
                .findFirst()
                .orElse(0);
    }

    private static ItemStack carried(ServerPlayer player) {
        ItemStack stack = CARRIED.get(player.getUUID());
        return stack == null ? ItemStack.EMPTY : stack;
    }

    private static void setCarried(ServerPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) clearCarried(player);
        else CARRIED.put(player.getUUID(), stack.copy());
    }

    private static void clearCarried(ServerPlayer player) {
        CARRIED.remove(player.getUUID());
    }

    private static void returnCarried(ServerPlayer player) {
        ItemStack stack = carried(player);
        if (stack.isEmpty()) return;
        ItemStack remaining = stack.copy();
        player.getInventory().add(remaining);
        if (!remaining.isEmpty()) player.drop(remaining, false);
        clearCarried(player);
    }

    private static void sendUnavailable(ServerPlayer player, boolean bound, String message) {
        PacketDistributor.sendToPlayer(player, new TabletChestSyncPacket(
                bound, false, message, "", "", BlockPos.ZERO, 0, 0, carried(player).copy(), List.of()));
    }

    private static void send(ServerPlayer player, BaseChestBlockEntity chest) {
        List<ChestSyncPacket.ItemEntry> items = new ArrayList<>();
        for (StoredItemStack entry : chest.getStoredItems()) {
            items.add(new ChestSyncPacket.ItemEntry(entry.prototype(), entry.count()));
        }
        items.sort(Comparator.<ChestSyncPacket.ItemEntry>comparingInt(ChestSyncPacket.ItemEntry::count).reversed());

        String dimension = chest.getLevel() == null ? "" : chest.getLevel().dimension().location().toString();
        PacketDistributor.sendToPlayer(player, new TabletChestSyncPacket(
                true,
                true,
                "",
                chest.getChestId(),
                dimension,
                chest.getBlockPos(),
                chest.getUsedCapacity(),
                chest.getMaxItemCapacity(),
                carried(player).copy(),
                items
        ));
    }
}
