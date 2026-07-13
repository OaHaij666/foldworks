package com.foldworks.dimension;

import com.foldworks.Foldworks;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ProductionSpaceManager {
    private static volatile ProductionSpaceManager instance;

    private final Map<UUID, ReturnAnchor> playerAnchors = new ConcurrentHashMap<>();
    // 仅在主线程（onServerTick / packet handler enqueueWork）访问，无需并发容器
    private final Deque<PendingTeleport> pendingTeleports = new ArrayDeque<>();

    private ProductionSpaceManager() {}

    public static ProductionSpaceManager getInstance() {
        if (instance == null) {
            synchronized (ProductionSpaceManager.class) {
                if (instance == null) instance = new ProductionSpaceManager();
            }
        }
        return instance;
    }

    public static SpaceData findSpaceAt(Level level, int blockX, int blockZ) {
        Optional<SpaceData> byDimension = SpaceDimensionService.getInstance().findByDimension(level);
        if (byDimension.isEmpty()) return null;

        SpaceData space = byDimension.get();
        int halfWidth = space.getWidth() / 2;
        int halfDepth = space.getDepth() / 2;
        return blockX >= -halfWidth && blockX < halfWidth && blockZ >= -halfDepth && blockZ < halfDepth ? space : null;
    }

    public BlockPos getSpaceCenter(UUID spaceId) {
        SpaceData space = SpaceManager.getInstance().getSpace(spaceId);
        return space == null ? new BlockPos(0, 64, 0) : SpaceDimensionService.getInstance().getSpawnPos(space);
    }

    public boolean isProductionSpaceDimension(ResourceKey<Level> dimension) {
        return SpaceDimensionService.getInstance().isSpaceDimension(dimension);
    }

    public void queueTeleportToSpace(ServerPlayer player, SpaceData space) {
        queueTeleportToSpace(player, space, 15);
    }

    public void queueTeleportToSpace(ServerPlayer player, SpaceData space, int delayTicks) {
        queueTeleportToSpace(player, space, delayTicks, false);
    }

    public void queueTeleportToNewSpace(ServerPlayer player, SpaceData space) {
        queueTeleportToSpace(player, space, 15, true);
    }

    private void queueTeleportToSpace(ServerPlayer player, SpaceData space, int delayTicks, boolean clearOutsideBoundary) {
        long readyAtTick = player.server.overworld().getGameTime() + Math.max(0, delayTicks);
        pendingTeleports.add(new PendingTeleport(player.getUUID(), space.getSpaceId(), readyAtTick, clearOutsideBoundary));
        player.sendSystemMessage(Component.literal("工域正在准备，请稍候...").withStyle(ChatFormatting.YELLOW));
    }

    public void onServerTick(MinecraftServer server) {
        if (pendingTeleports.isEmpty()) return;
        long currentTick = server.overworld().getGameTime();
        int processed = 0;
        // 队头元素未就绪时直接返回：ArrayDeque 保持入队顺序，readyAtTick 单调递增
        // 因此已就绪的元素一定连续位于队头
        while (!pendingTeleports.isEmpty() && processed < 100) {
            PendingTeleport pending = pendingTeleports.peekFirst();
            if (pending == null) return;
            if (pending.readyAtTick > currentTick) return;
            pendingTeleports.pollFirst();
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);
            SpaceData space = SpaceManager.getInstance().getSpace(pending.spaceId);
            if (player != null && space != null) {
                teleportToSpace(player, space, pending.clearOutsideBoundary);
            } else {
                Foldworks.LOGGER.warn("传送失败: 玩家={} 空间={}", player, pending.spaceId);
            }
            processed++;
        }
    }

    public void teleportToSpace(ServerPlayer player, SpaceData space) {
        teleportToSpace(player, space, false);
    }

    private void teleportToSpace(ServerPlayer player, SpaceData space, boolean clearOutsideBoundary) {
        if (!space.canAccess(player.getUUID())) {
            player.sendSystemMessage(Component.literal("你没有权限进入该工域").withStyle(ChatFormatting.RED));
            return;
        }

        MinecraftServer server = player.server;
        ServerLevel target;
        try {
            target = SpaceDimensionService.getInstance().loadOrCreate(server, space);
        } catch (RuntimeException e) {
            Foldworks.LOGGER.error("无法加载工域", e);
            player.sendSystemMessage(Component.literal("无法加载工域: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }

        if (!isProductionSpaceDimension(player.level().dimension())) {
            playerAnchors.put(player.getUUID(), new ReturnAnchor(
                    player.level().dimension(), player.position(), player.getYRot(), player.getXRot()));
            com.foldworks.space.SpaceStorage.markDirty();
        }

        if (clearOutsideBoundary && !space.isInfinite()) {
            SpaceDimensionService.getInstance().clearOutsideBoundary(target, space);
        }

        BlockPos center = SpaceDimensionService.getInstance().prepareSafeSpawn(target, space);
        applyWorldBorder(target, space);

        player.teleportTo(target, center.getX() + 0.5, center.getY(), center.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());

        player.connection.send(new ClientboundInitializeBorderPacket(target.getWorldBorder()));
    }

    private void applyWorldBorder(ServerLevel level, SpaceData space) {
        level.getWorldBorder().setCenter(0, 0);
        level.getWorldBorder().setSize(space.isInfinite() ? 59999968 : Math.max(space.getWidth(), space.getDepth()));
    }

    public void exitToReturnPosition(ServerPlayer player) {
        ReturnAnchor anchor = playerAnchors.remove(player.getUUID());
        com.foldworks.space.SpaceStorage.markDirty();
        MinecraftServer server = player.server;
        if (anchor != null) {
            ServerLevel target = server.getLevel(anchor.dimension);
            if (target != null) {
                player.teleportTo(target, anchor.position.x, anchor.position.y, anchor.position.z,
                        Set.of(), anchor.yRot, anchor.xRot);
                return;
            }
        }
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());
    }

    public void unloadSpaceLevel(MinecraftServer server, UUID spaceId) {
        SpaceData space = SpaceManager.getInstance().getSpace(spaceId);
        if (space != null) {
            SpaceDimensionService.getInstance().unload(server, space);
        }
    }

    public void reset() {
        playerAnchors.clear();
        pendingTeleports.clear();
    }

    public ListTag saveReturnAnchors() {
        ListTag anchors = new ListTag();
        for (Map.Entry<UUID, ReturnAnchor> entry : playerAnchors.entrySet()) {
            ReturnAnchor anchor = entry.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Player", entry.getKey());
            tag.putString("Dimension", anchor.dimension.location().toString());
            tag.putDouble("X", anchor.position.x);
            tag.putDouble("Y", anchor.position.y);
            tag.putDouble("Z", anchor.position.z);
            tag.putFloat("YRot", anchor.yRot);
            tag.putFloat("XRot", anchor.xRot);
            anchors.add(tag);
        }
        return anchors;
    }

    public void loadReturnAnchors(ListTag anchors) {
        playerAnchors.clear();
        for (Tag raw : anchors) {
            if (!(raw instanceof CompoundTag tag) || !tag.hasUUID("Player")) continue;
            ResourceLocation dim = ResourceLocation.tryParse(tag.getString("Dimension"));
            if (dim == null) continue;
            UUID playerId = tag.getUUID("Player");
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dim);
            Vec3 position = new Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
            playerAnchors.put(playerId, new ReturnAnchor(dimension, position, tag.getFloat("YRot"), tag.getFloat("XRot")));
        }
    }

    public List<SpaceData> getAccessibleSpaces(UUID playerId) {
        return SpaceManager.getInstance().getAccessibleSpaces(playerId);
    }

    private record ReturnAnchor(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {}
    private record PendingTeleport(UUID playerId, UUID spaceId, long readyAtTick, boolean clearOutsideBoundary) {}
}
