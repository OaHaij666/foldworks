package com.pockethomestead.network;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.client.ClientTabletChestCache;
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
        ItemStack carriedStack,
        List<ChestSyncPacket.ItemEntry> items
) implements CustomPacketPayload {
    public static final Type<TabletChestSyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PocketHomestead.MODID, "tablet_chest_sync"));

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
            ItemStack carriedStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int itemCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<ChestSyncPacket.ItemEntry> items = new ArrayList<>();
            for (int i = 0; i < itemCount; i++) {
                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                int count = ByteBufCodecs.VAR_INT.decode(buf);
                if (!stack.isEmpty() && count > 0) items.add(new ChestSyncPacket.ItemEntry(stack.copyWithCount(1), count));
            }
            return new TabletChestSyncPacket(bound, available, message, chestId, dimensionKey, pos,
                    usedCapacity, maxCapacity, carriedStack, Collections.unmodifiableList(items));
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
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, packet.carriedStack == null ? ItemStack.EMPTY : packet.carriedStack);
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
