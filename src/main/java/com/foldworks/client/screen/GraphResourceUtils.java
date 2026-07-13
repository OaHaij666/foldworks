package com.foldworks.client.screen;

import com.foldworks.client.ui.Theme;
import com.foldworks.network.TransferGraphSyncPacket;
import com.foldworks.transfer.TransferEdge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.List;

/**
 * 传输图资源/端口/边逻辑工具方法（纯静态，无状态）。
 */
public final class GraphResourceUtils {
    private GraphResourceUtils() {}

    // ── 端口标签与转换 ──

    public static String portLabel(String portKey) {
        if (TransferEdge.PORT_ALL.equals(portKey)) return "全部物品";
        if (TransferEdge.FLUID_ALL.equals(portKey)) return "全部流体";
        if (TransferEdge.ENERGY_FE.equals(portKey)) return "电力 FE";
        if (TransferEdge.STRESS_SU.equals(portKey)) return "应力 SU";
        if (portKey.startsWith(TransferEdge.ITEM_PREFIX) || portKey.startsWith(TransferEdge.FLUID_PREFIX)) return shortResource(portKey);
        return portKey;
    }

    public static String filterPort(String filter) {
        if (filter != null && filter.startsWith(TransferEdge.FLUID_PREFIX)) return filter;
        if (filter != null && filter.startsWith(TransferEdge.ITEM_PREFIX)) return filter;
        return TransferEdge.itemPort(filter);
    }

    public static String filterFromPort(String port) {
        if (port != null && port.startsWith(TransferEdge.FLUID_PREFIX)) return port;
        if (port != null && port.startsWith(TransferEdge.ITEM_PREFIX)) return port.substring(TransferEdge.ITEM_PREFIX.length());
        return port;
    }

    public static boolean isFilterPort(String port) {
        return port != null && (port.startsWith(TransferEdge.ITEM_PREFIX) || port.startsWith(TransferEdge.FLUID_PREFIX));
    }

    public static String normalizeFilterResource(String resourceId) {
        if (resourceId == null) return "";
        if (resourceId.startsWith(TransferEdge.ITEM_PREFIX)) return resourceId.substring(TransferEdge.ITEM_PREFIX.length());
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) return resourceId;
        return resourceId;
    }

    // ── 资源解析 ──

    public static Item resolveItem(String itemId) {
        if (itemId != null && itemId.startsWith(TransferEdge.ITEM_PREFIX)) itemId = itemId.substring(TransferEdge.ITEM_PREFIX.length());
        if (itemId != null && itemId.startsWith(TransferEdge.FLUID_PREFIX)) return null;
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    public static Fluid resolveFluid(String resourceId) {
        if (resourceId != null && resourceId.startsWith(TransferEdge.FLUID_PREFIX)) resourceId = resourceId.substring(TransferEdge.FLUID_PREFIX.length());
        ResourceLocation id = ResourceLocation.tryParse(resourceId);
        if (id == null) return null;
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
        return fluid == Fluids.EMPTY ? null : fluid;
    }

    public static boolean isFluidResource(String resourceId) {
        return resourceId != null && resourceId.startsWith(TransferEdge.FLUID_PREFIX);
    }

    public static String shortResource(String resourceId) {
        if (TransferEdge.FLUID_ALL.equals(resourceId)) return "全部流体";
        if (TransferEdge.PORT_ALL.equals(resourceId)) return "全部物品";
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return "电力 FE";
        if (TransferEdge.STRESS_SU.equals(resourceId)) return "应力 SU";
        if (resourceId == null) return "";
        if (resourceId.startsWith(TransferEdge.FLUID_PREFIX)) {
            Fluid fluid = resolveFluid(resourceId);
            if (fluid != null) return new FluidStack(fluid, 1).getHoverName().getString();
        } else {
            Item item = resolveItem(resourceId);
            if (item != null) return new ItemStack(item).getHoverName().getString();
        }
        String id = resourceId;
        if (id.startsWith(TransferEdge.ITEM_PREFIX)) id = id.substring(TransferEdge.ITEM_PREFIX.length());
        if (id.startsWith(TransferEdge.FLUID_PREFIX)) id = id.substring(TransferEdge.FLUID_PREFIX.length());
        return shortItem(id);
    }

    public static String resourceRateLabel(int value, String resourceId) {
        if (TransferEdge.ENERGY_FE.equals(resourceId)) return value + "FE/分";
        if (TransferEdge.STRESS_SU.equals(resourceId)) return value + "SU/分";
        return value + (isFluidResource(resourceId) ? "mB/分" : "/分");
    }

    public static String shortItem(String itemId) {
        int slash = itemId.indexOf(':');
        return slash >= 0 ? itemId.substring(slash + 1) : itemId;
    }

    public static String shortPos(long packed) {
        BlockPos pos = BlockPos.of(packed);
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // ── 边逻辑 ──

    public static int aggregateActualRate(List<TransferGraphSyncPacket.EdgeItemRateData> rows) {
        int total = 0;
        for (TransferGraphSyncPacket.EdgeItemRateData row : rows) total += row.actualRatePerMinute();
        return total;
    }

    public static String aggregateHealth(List<TransferGraphSyncPacket.EdgeItemRateData> rows, boolean enabled) {
        if (!enabled) return "DISABLED";
        boolean healthy = false;
        boolean source = false;
        boolean receiver = false;
        for (TransferGraphSyncPacket.EdgeItemRateData row : rows) {
            if ("DEADLOCKED".equals(row.health())) return "DEADLOCKED";
            if ("RECEIVER_BLOCKED".equals(row.health())) receiver = true;
            else if ("SOURCE_SHORTAGE".equals(row.health())) source = true;
            else if ("HEALTHY".equals(row.health())) healthy = true;
        }
        if (receiver) return "RECEIVER_BLOCKED";
        if (source) return "SOURCE_SHORTAGE";
        return healthy ? "HEALTHY" : "UNMEASURED";
    }

    public static String edgeRateLabel(TransferGraphSyncPacket.EdgeData edge) {
        if (edge.itemRates().isEmpty()) return "未测";
        return edge.itemRates().size() + "项 · " + aggregateActualRate(edge.itemRates()) + "/分";
    }

    public static int edgeVisualRate(TransferGraphSyncPacket.EdgeData edge) {
        return Math.max(edge.actualRatePerMinute(), aggregateActualRate(edge.itemRates()));
    }

    public static List<TransferGraphSyncPacket.EdgeItemRateData> defaultItemRatesForPort(String portKey) {
        if (portKey != null && (portKey.startsWith(TransferEdge.ITEM_PREFIX) || portKey.startsWith(TransferEdge.FLUID_PREFIX))) {
            return List.of(new TransferGraphSyncPacket.EdgeItemRateData(portKey, false, 1, 64, "UNMEASURED", 0, false));
        }
        return List.of();
    }

    public static List<TransferGraphSyncPacket.EdgeItemRateData> edgeRateRows(TransferGraphSyncPacket.EdgeData edge) {
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = new java.util.ArrayList<>(edge.itemRates());
        if (edge.fromPortKey().startsWith(TransferEdge.ITEM_PREFIX) || edge.fromPortKey().startsWith(TransferEdge.FLUID_PREFIX)) {
            String itemId = edge.fromPortKey();
            boolean exists = false;
            for (TransferGraphSyncPacket.EdgeItemRateData row : rows) {
                if (row.itemId().equals(itemId)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) rows.add(0, new TransferGraphSyncPacket.EdgeItemRateData(itemId, false, 1, 64, "UNMEASURED", 0, false));
        }
        return rows;
    }

    public static TransferGraphSyncPacket.EdgeItemRateData edgeRateRow(TransferGraphSyncPacket.EdgeData edge, String itemId) {
        if (edge == null || itemId == null) return null;
        for (TransferGraphSyncPacket.EdgeItemRateData row : edgeRateRows(edge)) {
            if (row.itemId().equals(itemId)) return row;
        }
        return null;
    }

    public static String defaultRateItemId(TransferGraphSyncPacket.EdgeData edge) {
        List<TransferGraphSyncPacket.EdgeItemRateData> rows = edgeRateRows(edge);
        return rows.isEmpty() ? null : rows.get(0).itemId();
    }

    public static TransferGraphSyncPacket.EdgeData copyEdge(TransferGraphSyncPacket.EdgeData e, boolean enabled, List<TransferGraphSyncPacket.EdgeItemRateData> itemRates) {
        return new TransferGraphSyncPacket.EdgeData(e.id(), e.pageId(), e.fromNodeId(), e.toNodeId(), e.fromPortKey(), e.toPortKey(),
                enabled, aggregateHealth(itemRates, enabled), aggregateActualRate(itemRates), List.copyOf(itemRates));
    }

    public static int healthTextColor(String health) {
        return switch (health) {
            case "HEALTHY" -> Theme.SUCCESS;
            case "SOURCE_SHORTAGE" -> 0xFFE0B43F;
            case "RECEIVER_BLOCKED" -> 0xFFE675AE;
            case "DEADLOCKED" -> 0xFF98283A;
            default -> Theme.TEXT_MUTED;
        };
    }

    public static String ratePerMinuteLabel(int rate) {
        if (rate >= 1000000) return (rate / 1000000) + "m/分";
        if (rate >= 10000) return (rate / 1000) + "k/分";
        return rate + "/分";
    }
}
