package com.pockethomestead.transfer;

import com.pockethomestead.blockentity.BaseChestBlockEntity;
import com.pockethomestead.space.SpacePermission;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public final class TransferGraphAccess {
    private TransferGraphAccess() {
    }

    public static boolean canView(ServerPlayer player, GraphKey key) {
        if (player == null || key == null || !key.isValid()) return false;
        return switch (key.kind()) {
            case PUBLIC -> true;
            case PRIVATE -> player.getUUID().equals(key.id());
            case PROTECTED -> TransferTeamStorage.get(player.server).can(player.getUUID(), key.id(), SpacePermission.AccessLevel.VIEW);
        };
    }

    public static boolean canWrite(ServerPlayer player, GraphKey key) {
        if (player == null || key == null || !key.isValid()) return false;
        return switch (key.kind()) {
            case PUBLIC -> true;
            case PRIVATE -> player.getUUID().equals(key.id());
            case PROTECTED -> TransferTeamStorage.get(player.server).can(player.getUUID(), key.id(), SpacePermission.AccessLevel.WRITE);
        };
    }

    public static boolean canManage(ServerPlayer player, GraphKey key) {
        if (player == null || key == null || !key.isValid()) return false;
        return switch (key.kind()) {
            case PUBLIC -> true;
            case PRIVATE -> player.getUUID().equals(key.id());
            case PROTECTED -> TransferTeamStorage.get(player.server).can(player.getUUID(), key.id(), SpacePermission.AccessLevel.MANAGE);
        };
    }

    public static boolean chestMatchesGraph(BaseChestBlockEntity chest, GraphKey key) {
        if (chest == null || key == null) return false;
        GraphKey chestKey = chest.getGraphKey();
        return chestKey != null && chestKey.sameTier(key);
    }

    public static boolean canEditPlayerRules(ServerPlayer player, TransferNode node) {
        return player != null
                && node != null
                && node.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY
                && Objects.equals(player.getUUID(), node.getTargetPlayerId());
    }
}
