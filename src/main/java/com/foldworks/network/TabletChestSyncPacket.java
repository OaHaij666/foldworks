package com.foldworks.network;

import com.foldworks.Foldworks;
import com.foldworks.client.ClientTabletChestCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record TabletChestSyncPacket(
        boolean bound,
        boolean available,
        String message,
        String chestId,
        String dimensionKey,
        BlockPos pos,
        int usedCapacity,
        int maxCapacity,
        boolean suiteUpgradeInstalled,
        ItemStack carriedStack,
        List<ItemStack> furnaceItems,
        int furnaceLitTime,
        int furnaceLitDuration,
        int furnaceCookingProgress,
        int furnaceCookingTotalTime,
        int furnaceMode,
        List<ItemStack> workbenchInputs,
        ItemStack workbenchResult,
        List<ItemStack> smithingInputs,
        ItemStack smithingResult,
        ItemStack stonecutterInput,
        List<ItemStack> stonecutterResults,
        int stonecutterSelectedIndex,
        ItemStack stonecutterResult,
        ItemStack suiteOrderTarget,
        int suiteOrderQuantity,
        int suiteMaxOrders,
        List<ItemStack> suiteTools,
        List<ItemStack> suiteResources,
        List<OrderEntry> suiteOrders,
        List<ChestSyncPacket.ItemEntry> items
) implements CustomPacketPayload {
    private static final int MAX_FURNACE_ITEMS = 3;
    private static final int MAX_WORKBENCH_INPUTS = 9;
    private static final int MAX_SMITHING_INPUTS = 3;
    private static final int MAX_STONECUTTER_RESULTS = 512;
    private static final int MAX_SUITE_TOOLS = 512;
    private static final int MAX_SUITE_RESOURCES = 2048;
    private static final int MAX_SUITE_ORDERS = 256;
    private static final int MAX_ITEMS = 4096;
    public record OrderEntry(int id, ItemStack target, int requested, int ready, int claimed,
                             String state, String reason, boolean canClaim,
                             boolean canRecover, boolean canDelete) {
    }

    public static final Type<TabletChestSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Foldworks.MODID, "tablet_chest_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TabletChestSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TabletChestSyncPacket decode(RegistryFriendlyByteBuf buf) {
            boolean bound = buf.readBoolean();
            boolean available = buf.readBoolean();
            String message = ByteBufCodecs.STRING_UTF8.decode(buf);
            String chestId = ByteBufCodecs.STRING_UTF8.decode(buf);
            String dimensionKey = ByteBufCodecs.STRING_UTF8.decode(buf);
            BlockPos pos = buf.readBlockPos();
            int usedCapacity = ByteBufCodecs.VAR_INT.decode(buf);
            int maxCapacity = ByteBufCodecs.VAR_INT.decode(buf);
            boolean suiteUpgradeInstalled = buf.readBoolean();
            ItemStack carriedStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int furnaceCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_FURNACE_ITEMS, "furnace items");
            List<ItemStack> furnaceItems = new ArrayList<>();
            for (int i = 0; i < furnaceCount; i++) {
                furnaceItems.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            int furnaceLitTime = ByteBufCodecs.VAR_INT.decode(buf);
            int furnaceLitDuration = ByteBufCodecs.VAR_INT.decode(buf);
            int furnaceCookingProgress = ByteBufCodecs.VAR_INT.decode(buf);
            int furnaceCookingTotalTime = ByteBufCodecs.VAR_INT.decode(buf);
            int furnaceMode = ByteBufCodecs.VAR_INT.decode(buf);
            int workbenchCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_WORKBENCH_INPUTS, "workbench inputs");
            List<ItemStack> workbenchInputs = new ArrayList<>();
            for (int i = 0; i < workbenchCount; i++) {
                workbenchInputs.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            ItemStack workbenchResult = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int smithingCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_SMITHING_INPUTS, "smithing inputs");
            List<ItemStack> smithingInputs = new ArrayList<>();
            for (int i = 0; i < smithingCount; i++) {
                smithingInputs.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            ItemStack smithingResult = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack stonecutterInput = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int stonecutterCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_STONECUTTER_RESULTS, "stonecutter results");
            List<ItemStack> stonecutterResults = new ArrayList<>();
            for (int i = 0; i < stonecutterCount; i++) {
                stonecutterResults.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            int stonecutterSelectedIndex = ByteBufCodecs.VAR_INT.decode(buf);
            ItemStack stonecutterResult = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            ItemStack suiteOrderTarget = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int suiteOrderQuantity = ByteBufCodecs.VAR_INT.decode(buf);
            int suiteMaxOrders = ByteBufCodecs.VAR_INT.decode(buf);
            int suiteToolCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_SUITE_TOOLS, "suite tools");
            List<ItemStack> suiteTools = new ArrayList<>();
            for (int i = 0; i < suiteToolCount; i++) {
                suiteTools.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            int suiteResourceCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_SUITE_RESOURCES, "suite resources");
            List<ItemStack> suiteResources = new ArrayList<>();
            for (int i = 0; i < suiteResourceCount; i++) {
                suiteResources.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
            }
            int orderCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_SUITE_ORDERS, "suite orders");
            List<OrderEntry> suiteOrders = new ArrayList<>();
            for (int i = 0; i < orderCount; i++) {
                int id = ByteBufCodecs.VAR_INT.decode(buf);
                ItemStack target = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                int requested = ByteBufCodecs.VAR_INT.decode(buf);
                int ready = ByteBufCodecs.VAR_INT.decode(buf);
                int claimed = ByteBufCodecs.VAR_INT.decode(buf);
                String state = ByteBufCodecs.STRING_UTF8.decode(buf);
                String reason = ByteBufCodecs.STRING_UTF8.decode(buf);
                boolean canClaim = buf.readBoolean();
                boolean canRecover = buf.readBoolean();
                boolean canDelete = buf.readBoolean();
                suiteOrders.add(new OrderEntry(id, target, requested, ready, claimed, state, reason, canClaim, canRecover, canDelete));
            }
            int itemCount = NetworkDecodeLimits.checkedCount(ByteBufCodecs.VAR_INT.decode(buf), MAX_ITEMS, "stored items");
            List<ChestSyncPacket.ItemEntry> items = new ArrayList<>();
            for (int i = 0; i < itemCount; i++) {
                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                int count = ByteBufCodecs.VAR_INT.decode(buf);
                if (!stack.isEmpty() && count > 0) items.add(new ChestSyncPacket.ItemEntry(stack.copyWithCount(1), count));
            }
            return new TabletChestSyncPacket(bound, available, message, chestId, dimensionKey, pos,
                    usedCapacity, maxCapacity, suiteUpgradeInstalled, carriedStack,
                    Collections.unmodifiableList(furnaceItems), furnaceLitTime, furnaceLitDuration,
                    furnaceCookingProgress, furnaceCookingTotalTime, furnaceMode,
                    Collections.unmodifiableList(workbenchInputs), workbenchResult,
                    Collections.unmodifiableList(smithingInputs), smithingResult,
                    stonecutterInput, Collections.unmodifiableList(stonecutterResults),
                    stonecutterSelectedIndex, stonecutterResult,
                    suiteOrderTarget, suiteOrderQuantity, suiteMaxOrders,
                    Collections.unmodifiableList(suiteTools), Collections.unmodifiableList(suiteResources),
                    Collections.unmodifiableList(suiteOrders),
                    Collections.unmodifiableList(items));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, TabletChestSyncPacket packet) {
            buf.writeBoolean(packet.bound);
            buf.writeBoolean(packet.available);
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.message == null ? "" : packet.message);
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.chestId == null ? "" : packet.chestId);
            ByteBufCodecs.STRING_UTF8.encode(buf, packet.dimensionKey == null ? "" : packet.dimensionKey);
            buf.writeBlockPos(packet.pos == null ? BlockPos.ZERO : packet.pos);
            ByteBufCodecs.VAR_INT.encode(buf, packet.usedCapacity);
            ByteBufCodecs.VAR_INT.encode(buf, packet.maxCapacity);
            buf.writeBoolean(packet.suiteUpgradeInstalled);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.carriedStack == null ? ItemStack.EMPTY : packet.carriedStack);
            List<ItemStack> furnaceItems = packet.furnaceItems == null ? List.of() : packet.furnaceItems;
            ByteBufCodecs.VAR_INT.encode(buf, furnaceItems.size());
            for (ItemStack stack : furnaceItems) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
            }
            ByteBufCodecs.VAR_INT.encode(buf, packet.furnaceLitTime);
            ByteBufCodecs.VAR_INT.encode(buf, packet.furnaceLitDuration);
            ByteBufCodecs.VAR_INT.encode(buf, packet.furnaceCookingProgress);
            ByteBufCodecs.VAR_INT.encode(buf, packet.furnaceCookingTotalTime);
            ByteBufCodecs.VAR_INT.encode(buf, packet.furnaceMode);
            List<ItemStack> workbenchInputs = packet.workbenchInputs == null ? List.of() : packet.workbenchInputs;
            ByteBufCodecs.VAR_INT.encode(buf, workbenchInputs.size());
            for (ItemStack stack : workbenchInputs) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
            }
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.workbenchResult == null ? ItemStack.EMPTY : packet.workbenchResult);
            List<ItemStack> smithingInputs = packet.smithingInputs == null ? List.of() : packet.smithingInputs;
            ByteBufCodecs.VAR_INT.encode(buf, smithingInputs.size());
            for (ItemStack stack : smithingInputs) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
            }
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.smithingResult == null ? ItemStack.EMPTY : packet.smithingResult);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.stonecutterInput == null ? ItemStack.EMPTY : packet.stonecutterInput);
            List<ItemStack> stonecutterResults = packet.stonecutterResults == null ? List.of() : packet.stonecutterResults;
            ByteBufCodecs.VAR_INT.encode(buf, stonecutterResults.size());
            for (ItemStack stack : stonecutterResults) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
            }
            ByteBufCodecs.VAR_INT.encode(buf, packet.stonecutterSelectedIndex);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.stonecutterResult == null ? ItemStack.EMPTY : packet.stonecutterResult);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.suiteOrderTarget == null ? ItemStack.EMPTY : packet.suiteOrderTarget);
            ByteBufCodecs.VAR_INT.encode(buf, packet.suiteOrderQuantity);
            ByteBufCodecs.VAR_INT.encode(buf, packet.suiteMaxOrders);
            List<ItemStack> suiteTools = packet.suiteTools == null ? List.of() : packet.suiteTools;
            ByteBufCodecs.VAR_INT.encode(buf, suiteTools.size());
            for (ItemStack stack : suiteTools) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
            }
            List<ItemStack> suiteResources = packet.suiteResources == null ? List.of() : packet.suiteResources;
            ByteBufCodecs.VAR_INT.encode(buf, suiteResources.size());
            for (ItemStack stack : suiteResources) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack == null ? ItemStack.EMPTY : stack);
            }
            List<OrderEntry> suiteOrders = packet.suiteOrders == null ? List.of() : packet.suiteOrders;
            ByteBufCodecs.VAR_INT.encode(buf, suiteOrders.size());
            for (OrderEntry order : suiteOrders) {
                ByteBufCodecs.VAR_INT.encode(buf, order.id());
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, order.target() == null ? ItemStack.EMPTY : order.target());
                ByteBufCodecs.VAR_INT.encode(buf, order.requested());
                ByteBufCodecs.VAR_INT.encode(buf, order.ready());
                ByteBufCodecs.VAR_INT.encode(buf, order.claimed());
                ByteBufCodecs.STRING_UTF8.encode(buf, order.state() == null ? "" : order.state());
                ByteBufCodecs.STRING_UTF8.encode(buf, order.reason() == null ? "" : order.reason());
                buf.writeBoolean(order.canClaim());
                buf.writeBoolean(order.canRecover());
                buf.writeBoolean(order.canDelete());
            }
            ByteBufCodecs.VAR_INT.encode(buf, packet.items.size());
            for (ChestSyncPacket.ItemEntry entry : packet.items) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, entry.stack().copyWithCount(1));
                ByteBufCodecs.VAR_INT.encode(buf, entry.count());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(TabletChestSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> ClientTabletChestCache.apply(packet));
    }
}
