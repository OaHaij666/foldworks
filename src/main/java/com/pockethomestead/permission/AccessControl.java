package com.pockethomestead.permission;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.blockentity.HomesteadChestAccess;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
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
        SpaceData space = containingSpace(chest.getLevel());
        return space != null && space.can(playerId, required);
    }

    public static BaseChestBlockEntity loadedChest(ServerPlayer player, String dimensionKey, BlockPos pos) {
        if (player == null || dimensionKey == null || pos == null) return null;
        ResourceLocation dimLoc = ResourceLocation.tryParse(dimensionKey);
        if (dimLoc == null) return null;
        ServerLevel level = player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                dimLoc
        ));
        return level == null ? null : HomesteadChestAccess.resolve(level.getBlockEntity(pos));
    }

    public static SpaceData containingSpace(Level level) {
        if (level == null) return null;
        return SpaceManager.getInstance().getSpaceByDimension(level.dimension().location());
    }

    public static void deny(ServerPlayer player) {
        if (player != null) {
            player.displayClientMessage(Component.literal("没有权限执行此操作"), true);
        }
    }
}
