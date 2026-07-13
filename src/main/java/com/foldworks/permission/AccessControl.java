package com.foldworks.permission;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.blockentity.FoldworksChestAccess;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import com.foldworks.space.SpacePermission;
import com.foldworks.transfer.GraphKey;
import com.foldworks.transfer.TransferTeamStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.UUID;

public final class AccessControl {
    private AccessControl() {
    }

    public static boolean canSpace(UUID playerId, SpaceData space, SpacePermission.AccessLevel required) {
        return space != null && space.can(playerId, required);
    }

    public static boolean canEnterSpace(ServerPlayer player, SpaceData space) {
        return player != null && canSpace(player.getUUID(), space, SpacePermission.AccessLevel.USE);
    }

    public static boolean canUseChest(ServerPlayer player, BaseChestBlockEntity chest) {
        return player != null && canUseChest(player.getUUID(), chest);
    }

    public static boolean canUseChest(UUID playerId, BaseChestBlockEntity chest) {
        return canChest(playerId, chest, SpacePermission.AccessLevel.USE);
    }

    public static boolean canViewChest(ServerPlayer player, BaseChestBlockEntity chest) {
        return player != null && canViewChest(player.getUUID(), chest);
    }

    public static boolean canViewChest(UUID playerId, BaseChestBlockEntity chest) {
        return canChest(playerId, chest, SpacePermission.AccessLevel.VIEW);
    }

    public static boolean canConfigureChest(ServerPlayer player, BaseChestBlockEntity chest) {
        return player != null && canConfigureChest(player.getUUID(), chest);
    }

    public static boolean canConfigureChest(UUID playerId, BaseChestBlockEntity chest) {
        return canChest(playerId, chest, SpacePermission.AccessLevel.WRITE);
    }

    public static boolean canManageChest(ServerPlayer player, BaseChestBlockEntity chest) {
        return player != null && canChest(player.getUUID(), chest, SpacePermission.AccessLevel.MANAGE);
    }

    public static boolean canChest(UUID playerId, BaseChestBlockEntity chest, SpacePermission.AccessLevel required) {
        if (playerId == null || chest == null) return false;
        if (playerId.equals(chest.getOwnerUUID())) return true;
        GraphKey.Kind kind = chest.getGraphKind();
        return switch (kind) {
            case PUBLIC -> true;
            case PRIVATE -> SpaceManager.getInstance()
                    .ownerPermission(chest.getOwnerUUID())
                    .can(playerId, false, required);
            case SPACE -> {
                if (ownerPermissionAllows(chest.getOwnerUUID(), playerId, required)) yield true;
                SpaceData space = chest.getContainingSpace();
                yield space != null && space.can(playerId, required);
            }
            case PROTECTED -> {
                if (ownerPermissionAllows(chest.getOwnerUUID(), playerId, required)) yield true;
                UUID teamId = chest.getGraphTeamId();
                if (teamId == null || chest.getLevel() == null || chest.getLevel().isClientSide) yield false;
                if (!(chest.getLevel() instanceof ServerLevel serverLevel)) yield false;
                yield TransferTeamStorage.get(serverLevel.getServer()).can(playerId, teamId, required);
            }
        };
    }

    private static boolean ownerPermissionAllows(UUID ownerId, UUID playerId, SpacePermission.AccessLevel required) {
        if (ownerId == null || playerId == null || ownerId.equals(playerId)) return false;
        return SpaceManager.getInstance().ownerPermission(ownerId).levelFor(playerId).allows(required);
    }

    public static BaseChestBlockEntity loadedChest(ServerPlayer player, String dimensionKey, BlockPos pos) {
        if (player == null || dimensionKey == null || pos == null) return null;
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionKey);
        if (dimLoc == null) return null;
        ServerLevel level = player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
        ));
        return level == null ? null : FoldworksChestAccess.resolve(level.getBlockEntity(pos));
    }

    public static SpaceData containingSpace(Level level) {
        if (level == null) return null;
        return SpaceManager.getInstance().getSpaceByDimension(level.dimension().location());
    }

    public static void deny(ServerPlayer player) {
        if (player != null) {
            player.displayClientMessage(Component.translatable("foldworks.permission.denied"), true);
        }
    }
}
