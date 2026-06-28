package com.pockethomestead.transfer;

import com.pockethomestead.space.SpacePermission;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TransferTeamStorage extends SavedData {
    public static final String DATA_NAME = "pockethomestead_transfer_teams";

    private final Map<UUID, TransferTeam> teams = new LinkedHashMap<>();

    public static TransferTeamStorage get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public TransferTeam getTeam(UUID id) {
        return teams.get(id);
    }

    public Collection<TransferTeam> getTeams() {
        return teams.values();
    }

    public List<TransferTeam> teamsVisibleTo(UUID playerId) {
        List<TransferTeam> result = new ArrayList<>();
        for (TransferTeam team : teams.values()) {
            if (team.can(playerId, SpacePermission.AccessLevel.VIEW)) result.add(team);
        }
        result.sort(Comparator.comparing(TransferTeam::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public TransferTeam createTeam(UUID owner, String name) {
        TransferTeam team = new TransferTeam(UUID.randomUUID(), name, owner);
        teams.put(team.id(), team);
        setDirty();
        return team;
    }

    public boolean can(UUID playerId, UUID teamId, SpacePermission.AccessLevel required) {
        TransferTeam team = teams.get(teamId);
        return team != null && team.can(playerId, required);
    }

    public static TransferTeamStorage load(CompoundTag tag, HolderLookup.Provider reg) {
        TransferTeamStorage storage = new TransferTeamStorage();
        ListTag list = tag.getList("Teams", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            TransferTeam team = TransferTeam.load(list.getCompound(i));
            storage.teams.put(team.id(), team);
        }
        return storage;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        ListTag list = new ListTag();
        for (TransferTeam team : teams.values()) list.add(team.save());
        tag.put("Teams", list);
        return tag;
    }

    public static SavedData.Factory<TransferTeamStorage> factory() {
        return new SavedData.Factory<>(TransferTeamStorage::new, TransferTeamStorage::load);
    }
}
