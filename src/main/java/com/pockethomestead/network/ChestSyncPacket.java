package com.pockethomestead.network;

import net.minecraft.client.Minecraft;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ChestSyncPacket(
    String chestId,
    BlockPos blockPos,
    boolean transferEnabled,
    boolean voidModeEnabled,
    boolean offlineSnapshotEnabled,
    boolean spaceOfflineSimulationEnabled,
    int transferRateLimit,
    int nextTransferSeconds,
    int maxCapacity,
    int maxFluidCapacityMb,
    int maxFluidTypes,
    int maxFluidCapacityPerTypeMb,
    int energyStored,
    int maxEnergyStored,
    int energyTransferLimit,
    int networkBandwidth,
    int stressBandwidthUsed,
    int remainingTransferBandwidth,
    boolean stressUpgradeInstalled,
    boolean createLoaded,
    int stressOutputSpeedRpm,
    boolean stressOutputReversed,
    String graphKind,
    String graphTeamId,
    boolean productionStatsEnabled,
    String productionGroupId,
    List<Integer> upgradeCounts,
    List<SideConfigEntry> sideConfig,
    List<ItemEntry> items,
    Map<String, Integer> fluids
) implements CustomPacketPayload {
    public record ItemEntry(ItemStack stack, int count) {}
    public record SideConfigEntry(String kind, String side, String mode) {}

    public static final Type<ChestSyncPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("pockethomestead", "chest_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ChestSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ChestSyncPacket decode(RegistryFriendlyByteBuf buf) {
            String chestId = ByteBufCodecs.STRING_UTF8.decode(buf);
            BlockPos blockPos = buf.readBlockPos();
            boolean transferEnabled = buf.readBoolean();
            boolean voidModeEnabled = buf.readBoolean();
            boolean offlineSnapshotEnabled = buf.readBoolean();
            boolean spaceOfflineSimulationEnabled = buf.readBoolean();
            int transferRateLimit = ByteBufCodecs.VAR_INT.decode(buf);
            int nextTransferSeconds = ByteBufCodecs.VAR_INT.decode(buf);
            int maxCapacity = ByteBufCodecs.VAR_INT.decode(buf);
            int maxFluidCapacityMb = ByteBufCodecs.VAR_INT.decode(buf);
            int maxFluidTypes = ByteBufCodecs.VAR_INT.decode(buf);
            int maxFluidCapacityPerTypeMb = ByteBufCodecs.VAR_INT.decode(buf);
            int energyStored = ByteBufCodecs.VAR_INT.decode(buf);
            int maxEnergyStored = ByteBufCodecs.VAR_INT.decode(buf);
            int energyTransferLimit = ByteBufCodecs.VAR_INT.decode(buf);
            int networkBandwidth = ByteBufCodecs.VAR_INT.decode(buf);
            int stressBandwidthUsed = ByteBufCodecs.VAR_INT.decode(buf);
            int remainingTransferBandwidth = ByteBufCodecs.VAR_INT.decode(buf);
            boolean stressUpgradeInstalled = buf.readBoolean();
            boolean createLoaded = buf.readBoolean();
            int stressOutputSpeedRpm = ByteBufCodecs.VAR_INT.decode(buf);
            boolean stressOutputReversed = buf.readBoolean();
            String graphKind = ByteBufCodecs.STRING_UTF8.decode(buf);
            String graphTeamId = ByteBufCodecs.STRING_UTF8.decode(buf);
            boolean productionStatsEnabled = buf.readBoolean();
            String productionGroupId = ByteBufCodecs.STRING_UTF8.decode(buf);

            int upgradeCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<Integer> upgrades = new ArrayList<>();
            for (int i = 0; i < upgradeCount; i++) upgrades.add(ByteBufCodecs.VAR_INT.decode(buf));

            int sideCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<SideConfigEntry> sideConfig = new ArrayList<>();
            for (int i = 0; i < sideCount; i++) {
                sideConfig.add(new SideConfigEntry(
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf),
                        ByteBufCodecs.STRING_UTF8.decode(buf)
                ));
            }

            int itemCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<ItemEntry> items = new ArrayList<>();
            for (int i = 0; i < itemCount; i++) {
                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                int count = ByteBufCodecs.VAR_INT.decode(buf);
                if (!stack.isEmpty() && count > 0) items.add(new ItemEntry(stack.copyWithCount(1), count));
            }

            int fluidCount = ByteBufCodecs.VAR_INT.decode(buf);
            Map<String, Integer> fluids = new LinkedHashMap<>();
            for (int i = 0; i < fluidCount; i++) {
                fluids.put(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_INT.decode(buf));
            }

            return new ChestSyncPacket(chestId, blockPos, transferEnabled, voidModeEnabled, offlineSnapshotEnabled, spaceOfflineSimulationEnabled,
                    transferRateLimit, nextTransferSeconds,
                    maxCapacity, maxFluidCapacityMb, maxFluidTypes, maxFluidCapacityPerTypeMb,
                    energyStored, maxEnergyStored, energyTransferLimit,
                    networkBandwidth, stressBandwidthUsed, remainingTransferBandwidth,
                    stressUpgradeInstalled, createLoaded,
                    stressOutputSpeedRpm, stressOutputReversed,
                    graphKind, graphTeamId,
                    productionStatsEnabled, productionGroupId,
                    Collections.unmodifiableList(upgrades), Collections.unmodifiableList(sideConfig),
                    Collections.unmodifiableList(items), Collections.unmodifiableMap(fluids));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ChestSyncPacket pkt) {
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.chestId);
            buf.writeBlockPos(pkt.blockPos);
            buf.writeBoolean(pkt.transferEnabled);
            buf.writeBoolean(pkt.voidModeEnabled);
            buf.writeBoolean(pkt.offlineSnapshotEnabled);
            buf.writeBoolean(pkt.spaceOfflineSimulationEnabled);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.transferRateLimit);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.nextTransferSeconds);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.maxCapacity);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.maxFluidCapacityMb);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.maxFluidTypes);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.maxFluidCapacityPerTypeMb);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.energyStored);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.maxEnergyStored);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.energyTransferLimit);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.networkBandwidth);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.stressBandwidthUsed);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.remainingTransferBandwidth);
            buf.writeBoolean(pkt.stressUpgradeInstalled);
            buf.writeBoolean(pkt.createLoaded);
            ByteBufCodecs.VAR_INT.encode(buf, pkt.stressOutputSpeedRpm);
            buf.writeBoolean(pkt.stressOutputReversed);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphKind);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.graphTeamId);
            buf.writeBoolean(pkt.productionStatsEnabled);
            ByteBufCodecs.STRING_UTF8.encode(buf, pkt.productionGroupId);

            ByteBufCodecs.VAR_INT.encode(buf, pkt.upgradeCounts.size());
            for (Integer count : pkt.upgradeCounts) ByteBufCodecs.VAR_INT.encode(buf, count == null ? 0 : count);

            ByteBufCodecs.VAR_INT.encode(buf, pkt.sideConfig.size());
            for (SideConfigEntry entry : pkt.sideConfig) {
                ByteBufCodecs.STRING_UTF8.encode(buf, entry.kind);
                ByteBufCodecs.STRING_UTF8.encode(buf, entry.side);
                ByteBufCodecs.STRING_UTF8.encode(buf, entry.mode);
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.items.size());
            for (ItemEntry e : pkt.items) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, e.stack().copyWithCount(1));
                ByteBufCodecs.VAR_INT.encode(buf, e.count());
            }

            ByteBufCodecs.VAR_INT.encode(buf, pkt.fluids.size());
            for (Map.Entry<String, Integer> e : pkt.fluids.entrySet()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.getKey());
                ByteBufCodecs.VAR_INT.encode(buf, e.getValue());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ChestSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            com.pockethomestead.blockentity.BaseChestBlockEntity menuChest = null;
            if (mc.screen instanceof com.pockethomestead.client.screen.BaseChestScreen<?> screen) {
                screen.cacheConfig(packet);
                menuChest = screen.getMenu().getBlockEntity();
            }
            if (menuChest == null
                    && mc.player != null
                    && mc.player.containerMenu instanceof com.pockethomestead.menu.BaseChestMenu menu) {
                menuChest = menu.getBlockEntity();
            }

            if (menuChest != null) {
                menuChest.applySideConfigFromSync(packet.sideConfig());
            }
            if (mc.level == null) return;
            net.minecraft.world.level.block.entity.BlockEntity rawBlockEntity = mc.level.getBlockEntity(packet.blockPos());
            com.pockethomestead.blockentity.BaseChestBlockEntity worldChest =
                    com.pockethomestead.blockentity.HomesteadChestAccess.resolve(rawBlockEntity);
            if (worldChest != null) {
                worldChest.applySideConfigFromSync(packet.sideConfig());
                if (rawBlockEntity != null) rawBlockEntity.requestModelDataUpdate();
                net.minecraft.world.level.block.state.BlockState state = mc.level.getBlockState(packet.blockPos());
                mc.level.sendBlockUpdated(packet.blockPos(), state, state, 8);
            }
        });
    }
}
