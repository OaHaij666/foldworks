package com.foldworks.transfer;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class TransferGraphStorage extends SavedData {
    public static final String DATA_NAME = "foldworks_transfer_graphs";

    private final Map<GraphKey, TransferGraph> graphs = new LinkedHashMap<>();

    public static TransferGraphStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public TransferGraph graphFor(UUID owner) {
        return graphFor(GraphKey.privateGraph(owner));
    }

    public TransferGraph graphFor(GraphKey key) {
        GraphKey safeKey = key == null ? GraphKey.publicGraph() : key;
        return graphs.computeIfAbsent(safeKey, TransferGraph::new);
    }

    public TransferGraph getGraph(GraphKey key) {
        return key == null ? null : graphs.get(key);
    }

    public void replaceGraph(UUID owner, TransferGraph graph) {
        replaceGraph(GraphKey.privateGraph(owner), graph);
    }

    public void replaceGraph(GraphKey key, TransferGraph graph) {
        GraphKey safeKey = key == null ? GraphKey.publicGraph() : key;
        graphs.put(safeKey, graph);
        setDirty();
    }

    public void removeGraph(GraphKey key) {
        if (key == null) return;
        if (graphs.remove(key) != null) setDirty();
    }

    public Collection<TransferGraph> getGraphs() { return graphs.values(); }

    public boolean updateChestId(String oldChestId, String newChestId, String dimensionKey, net.minecraft.core.BlockPos pos) {
        boolean changed = false;
        for (TransferGraph graph : graphs.values()) {
            changed |= graph.updateChestId(oldChestId, newChestId, dimensionKey, pos);
        }
        if (changed) setDirty();
        return changed;
    }

    public boolean relocateChest(String chestId, String oldDimensionKey, net.minecraft.core.BlockPos oldPos,
                                 String newDimensionKey, net.minecraft.core.BlockPos newPos) {
        boolean changed = false;
        for (TransferGraph graph : graphs.values()) {
            changed |= graph.relocateChest(chestId, oldDimensionKey, oldPos, newDimensionKey, newPos);
        }
        if (changed) setDirty();
        return changed;
    }

    public static TransferGraphStorage load(CompoundTag tag, HolderLookup.Provider reg) {
        TransferGraphStorage storage = new TransferGraphStorage();
        ListTag list = tag.getList("Graphs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            TransferGraph graph = TransferGraph.load(list.getCompound(i));
            storage.graphs.put(graph.getKey(), graph);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag list = new ListTag();
        for (TransferGraph graph : graphs.values()) list.add(graph.save());
        tag.put("Graphs", list);
        return tag;
    }

    public static SavedData.Factory<TransferGraphStorage> factory() {
        return new SavedData.Factory<>(TransferGraphStorage::new, TransferGraphStorage::load);
    }
}
