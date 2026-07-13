package com.foldworks.client;

import com.foldworks.network.TransferGraphSyncPacket;
import com.foldworks.transfer.TransferGraph;

import java.util.List;

public class ClientTransferGraphCache {
    private static String graphKind = "PRIVATE";
    private static String graphId = "";
    private static List<TransferGraphSyncPacket.GraphOptionData> graphOptions = List.of();
    private static List<TransferGraphSyncPacket.TeamData> teams = List.of();
    private static List<TransferGraphSyncPacket.PageData> pages = List.of();
    private static List<TransferGraphSyncPacket.NodeData> nodes = List.of();
    private static List<TransferGraphSyncPacket.EdgeData> edges = List.of();
    private static List<TransferGraphSyncPacket.ChestData> chests = List.of();
    private static List<com.foldworks.network.TransferGraphValidationPacket.IssueData> validationIssues = List.of();

    public static void update(TransferGraphSyncPacket packet) {
        graphKind = packet.graphKind();
        graphId = packet.graphId();
        graphOptions = List.copyOf(packet.graphOptions());
        teams = List.copyOf(packet.teams());
        pages = List.copyOf(packet.pages());
        nodes = List.copyOf(packet.nodes());
        edges = List.copyOf(packet.edges());
        chests = List.copyOf(packet.chests());
    }

    public static String graphKind() { return graphKind; }
    public static String graphId() { return graphId; }
    public static List<TransferGraphSyncPacket.GraphOptionData> graphOptions() { return graphOptions; }
    public static List<TransferGraphSyncPacket.TeamData> teams() { return teams; }
    public static List<TransferGraphSyncPacket.PageData> pages() { return pages; }
    public static List<TransferGraphSyncPacket.NodeData> nodes() { return nodes; }
    public static List<TransferGraphSyncPacket.EdgeData> edges() { return edges; }
    public static List<TransferGraphSyncPacket.ChestData> chests() { return chests; }
    public static List<com.foldworks.network.TransferGraphValidationPacket.IssueData> validationIssues() { return validationIssues; }

    public static void updateValidation(List<com.foldworks.network.TransferGraphValidationPacket.IssueData> issues) {
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
