package com.pockethomestead.space;

import com.pockethomestead.config.ModConfig;
import net.minecraft.server.level.ServerPlayer;

public final class SpaceExperienceCost {
    private SpaceExperienceCost() {
    }

    public static int costFor(int width, int depth, boolean infinite) {
        if (infinite) return Math.max(0, ModConfig.EXP_COST_INFINITE.get());
        long cost = (long) Math.max(0, width) * Math.max(0, depth) * Math.max(0, ModConfig.EXP_COST_PER_BLOCK.get());
        return cost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cost;
    }

    public static boolean canAfford(ServerPlayer player, int cost) {
        return player != null && (player.isCreative() || cost <= 0 || totalExperienceEstimate(player) >= cost);
    }

    public static void charge(ServerPlayer player, int cost) {
        if (player != null && !player.isCreative() && cost > 0) {
            player.giveExperiencePoints(-cost);
        }
    }

    public static int chargeableCost(ServerPlayer player, int width, int depth, boolean infinite) {
        if (player == null || player.isCreative()) return 0;
        return costFor(width, depth, infinite);
    }

    private static long totalExperienceEstimate(ServerPlayer player) {
        return Math.round(player.experienceLevel * 100.0 + player.experienceProgress * player.getXpNeededForNextLevel());
    }
}
