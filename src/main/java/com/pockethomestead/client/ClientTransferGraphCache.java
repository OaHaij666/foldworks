package com.pockethomestead.client;

import com.pockethomestead.network.TransferGraphSyncPacket;
import com.pockethomestead.transfer.TransferGraph;

import java.util.List;

public class ClientTransferGraphCache {
    private static List<TransferGraphSyncPacket.PageData> pages = List.of();
    private static List<TransferGraphSyncPacket.NodeData> nodes = List.of();
    private static List<TransferGraphSyncPacket.EdgeData> edges = List.of();
    private static List<TransferGraphSyncPacket.ChestData> supplyChests = List.of();
    private static List<TransferGraphSyncPacket.ChestData> pickupChests = List.of();
    private static List<com.pockethomestead.network.TransferGraphValidationPacket.IssueData> validationIssues = List.of();

    public static void update(TransferGraphSyncPacket packet) {
        pages = List.copyOf(packet.pages());
        nodes = List.copyOf(packet.nodes());
        edges = List.copyOf(packet.edges());
        supplyChests = List.copyOf(packet.supplyChests());
        pickupChests = List.copyOf(packet.pickupChests());
    }

    public static List<TransferGraphSyncPacket.PageData> pages() { return pages; }
    public static List<TransferGraphSyncPacket.NodeData> nodes() { return nodes; }
    public static List<TransferGraphSyncPacket.EdgeData> edges() { return edges; }
    public static List<TransferGraphSyncPacket.ChestData> supplyChests() { return supplyChests; }
    public static List<TransferGraphSyncPacket.ChestData> pickupChests() { return pickupChests; }
    public static List<com.pockethomestead.network.TransferGraphValidationPacket.IssueData> validationIssues() { return validationIssues; }

    public static void updateValidation(List<com.pockethomestead.network.TransferGraphValidationPacket.IssueData> issues) {
        validationIssues = List.copyOf(issues);
    }

    public static TransferGraphSyncPacket.PageData page(String id) {
        for (TransferGraphSyncPacket.PageData page : pages) if (page.id().equals(id)) return page;
        return null;
    }

    public static TransferGraphSyncPacket.PageData firstPage() {
        if (pages.isEmpty()) return new TransferGraphSyncPacket.PageData(TransferGraph.DEFAULT_PAGE_ID, "默认页", true, 0);
        return pages.get(0);
    }

    public static TransferGraphSyncPacket.NodeData node(String id) {
        for (TransferGraphSyncPacket.NodeData node : nodes) if (node.id().equals(id)) return node;
        return null;
    }
}
