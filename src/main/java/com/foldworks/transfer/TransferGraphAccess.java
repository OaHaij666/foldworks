package com.foldworks.transfer;

import com.foldworks.blockentity.BaseChestBlockEntity;
import com.foldworks.space.SpaceData;
import com.foldworks.space.SpaceManager;
import com.foldworks.space.SpacePermission;
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
            case SPACE -> canSpace(player, key, SpacePermission.AccessLevel.VIEW);
        };
    }

    public static boolean canWrite(ServerPlayer player, GraphKey key) {
        if (player == null || key == null || !key.isValid()) return false;
        return switch (key.kind()) {
            case PUBLIC -> true;
            case PRIVATE -> player.getUUID().equals(key.id());
            case PROTECTED -> TransferTeamStorage.get(player.server).can(player.getUUID(), key.id(), SpacePermission.AccessLevel.WRITE);
            case SPACE -> canSpace(player, key, SpacePermission.AccessLevel.WRITE);
        };
    }

    public static boolean canManage(ServerPlayer player, GraphKey key) {
        if (player == null || key == null || !key.isValid()) return false;
        return switch (key.kind()) {
            case PUBLIC -> true;
            case PRIVATE -> player.getUUID().equals(key.id());
            case PROTECTED -> TransferTeamStorage.get(player.server).can(player.getUUID(), key.id(), SpacePermission.AccessLevel.MANAGE);
            case SPACE -> canSpace(player, key, SpacePermission.AccessLevel.MANAGE);
        };
    }

    public static boolean chestMatchesGraph(BaseChestBlockEntity chest, GraphKey key) {
        if (chest == null || key == null) return false;
        GraphKey chestKey = chest.getGraphKey();
        return chestKey != null && chestKey.sameTier(key);
    }

    private static boolean canSpace(ServerPlayer player, GraphKey key, SpacePermission.AccessLevel level) {
        if (player == null || key == null || key.id() == null) return false;
        SpaceData space = SpaceManager.getInstance().getSpace(key.id());
        return space != null && space.can(player.getUUID(), level);
    }

    public static boolean canEditPlayerRules(ServerPlayer player, TransferNode node) {
        return player != null
                && node != null
                && node.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY
                && Objects.equals(player.getUUID(), node.getTargetPlayerId());
    }
}
