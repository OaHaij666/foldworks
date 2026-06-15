package com.pockethomestead.dimension;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PocketDimensionManager {
    private static volatile PocketDimensionManager instance;

    private final Map<UUID, ReturnAnchor> playerAnchors = new ConcurrentHashMap<>();
    private final Queue<PendingTeleport> pendingTeleports = new ConcurrentLinkedQueue<>();

    private PocketDimensionManager() {}

    public static PocketDimensionManager getInstance() {
        if (instance == null) {
            synchronized (PocketDimensionManager.class) {
                if (instance == null) instance = new PocketDimensionManager();
            }
        }
        return instance;
    }

    public static SpaceData findSpaceAt(Level level, int blockX, int blockZ) {
        Optional<SpaceData> byDimension = SpaceDimensionService.getInstance()
                .findByDimension(SpaceManager.getInstance().getAllSpaces(), level);
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

    public boolean isPocketDimension(ResourceKey<Level> dimension) {
        return SpaceDimensionService.getInstance().isSpaceDimension(dimension);
    }

    public void queueTeleportToSpace(ServerPlayer player, SpaceData space) {
        queueTeleportToSpace(player, space, 15);
    }

    public void queueTeleportToSpace(ServerPlayer player, SpaceData space, int delayTicks) {
        pendingTeleports.add(new PendingTeleport(player.getUUID(), space.getSpaceId(), delayTicks));
        player.sendSystemMessage(Component.literal("口袋空间正在准备，请稍候...").withStyle(ChatFormatting.YELLOW));
        PocketHomestead.LOGGER.info("排队传送玩家 {} 到空间 {}, 延迟{}tick",
                player.getName().getString(), space.getSpaceId(), delayTicks);
    }

    public void onServerTick(MinecraftServer server) {
        int processed = 0;
        while (!pendingTeleports.isEmpty() && processed < 100) {
            PendingTeleport pending = pendingTeleports.poll();
            if (pending == null) return;
            if (pending.delayTicks > 0) {
                pendingTeleports.add(new PendingTeleport(pending.playerId, pending.spaceId, pending.delayTicks - 1));
                processed++;
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);
            SpaceData space = SpaceManager.getInstance().getSpace(pending.spaceId);
            if (player != null && space != null) {
                teleportToSpace(player, space);
            } else {
                PocketHomestead.LOGGER.warn("传送失败: 玩家={} 空间={}", player, pending.spaceId);
            }
            processed++;
        }
    }

    public void teleportToSpace(ServerPlayer player, SpaceData space) {
        if (!space.canAccess(player.getUUID())) {
            player.sendSystemMessage(Component.literal("你没有权限进入该口袋空间").withStyle(ChatFormatting.RED));
            return;
        }

        MinecraftServer server = player.server;
        ServerLevel target;
        try {
            target = SpaceDimensionService.getInstance().loadOrCreate(server, space);
        } catch (RuntimeException e) {
            PocketHomestead.LOGGER.error("无法加载口袋空间", e);
            player.sendSystemMessage(Component.literal("无法加载口袋空间: " + e.getMessage()).withStyle(ChatFormatting.RED));
            return;
        }

        if (!isPocketDimension(player.level().dimension())) {
            playerAnchors.put(player.getUUID(), new ReturnAnchor(
                    player.level().dimension(), player.position(), player.getYRot(), player.getXRot()));
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

    public List<SpaceData> getAccessibleSpaces(UUID playerId) {
        return SpaceManager.getInstance().getAccessibleSpaces(playerId);
    }

    private record ReturnAnchor(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {}
    private record PendingTeleport(UUID playerId, UUID spaceId, int delayTicks) {}
}
