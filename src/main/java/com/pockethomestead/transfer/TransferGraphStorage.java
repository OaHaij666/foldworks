package com.pockethomestead.transfer;

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
    public static final String DATA_NAME = "pockethomestead_transfer_graphs";

    private final Map<UUID, TransferGraph> graphs = new LinkedHashMap<>();

    public static TransferGraphStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public TransferGraph graphFor(UUID owner) {
        return graphs.computeIfAbsent(owner, TransferGraph::new);
    }

    public void replaceGraph(UUID owner, TransferGraph graph) {
        graphs.put(owner, graph);
        setDirty();
    }

    public Collection<TransferGraph> getGraphs() { return graphs.values(); }

    public static TransferGraphStorage load(CompoundTag tag, HolderLookup.Provider reg) {
        TransferGraphStorage storage = new TransferGraphStorage();
        ListTag list = tag.getList("Graphs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            TransferGraph graph = TransferGraph.load(list.getCompound(i));
            storage.graphs.put(graph.getOwner(), graph);
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
