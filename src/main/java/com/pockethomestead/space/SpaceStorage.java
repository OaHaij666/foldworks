package com.pockethomestead.space;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.dimension.PocketDimensionManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpaceStorage extends SavedData {
    private static SpaceStorage instance;

    public SpaceStorage() {
        instance = this;
        // 新存档没有 pockethomestead_spaces.dat 时会走这个构造器而不是 load()。
        // 必须清空 JVM 单例缓存，否则上一个存档的空间会被写入新存档，造成跨存档泄漏。
        SpaceManager.getInstance().clearSpaces();
        PocketDimensionManager.getInstance().reset();
    }
    public static SpaceStorage getInstance() { return instance; }
    public static void clearInstance() { instance = null; }
    public static void markDirty() { if (instance != null) instance.setDirty(); }

    public static SpaceStorage load(CompoundTag tag, HolderLookup.Provider reg) {
        // 构造器已清空 SpaceManager/PocketDimensionManager 单例，此处不再重复 reset
        int version = tag.contains("FormatVersion") ? tag.getInt("FormatVersion") : 0;
        SpaceStorage s = new SpaceStorage();
        List<SpaceData> list = new ArrayList<>();
        ListTag lt = tag.getList("Spaces", ListTag.TAG_COMPOUND);
        for (int i = 0; i < lt.size(); i++) {
            SpaceData sd = deserialize(lt.getCompound(i));
            if (sd != null) list.add(sd);
        }
        SpaceManager.getInstance().loadSpaces(list);
        return s;
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        tag.putInt("FormatVersion", 1);
        ListTag lt = new ListTag();
        for (SpaceData s : SpaceManager.getInstance().getAllSpaces()) lt.add(serialize(s));
        tag.put("Spaces", lt);
        return tag;
    }

    private static CompoundTag serialize(SpaceData s) {
        CompoundTag t = new CompoundTag();
        t.putUUID("SpaceId", s.getSpaceId());
        t.putUUID("OwnerId", s.getOwnerId());
        t.putString("DimensionId", s.getDimensionId().toString());
        t.putInt("Width", s.getWidth());
        t.putInt("Height", s.getHeight());
        t.putInt("Depth", s.getDepth());
        t.putString("TerrainType", s.getTerrainType().name());
        t.putString("Biome", s.getBiome());
        if (s.getSourceDimension() != null) t.putString("SourceDimension", s.getSourceDimension().toString());
        t.putBoolean("MobSpawning", s.isMobSpawning());
        t.putBoolean("StructureGeneration", s.isStructureGeneration());
        t.putBoolean("Infinite", s.isInfinite());
        t.putFloat("TerrainAmplitude", s.getTerrainAmplitude());
        t.putString("Name", s.getName());
        t.putBoolean("OfflineSimulationEnabled", s.isOfflineSimulationEnabled());
        t.putString("PermMode", s.getPermission().getMode().name());
        t.putString("ProtectedLevel", s.getPermission().getProtectedLevel().name());
        t.putString("PublicLevel", s.getPermission().getPublicLevel().name());
        ListTag members = new ListTag();
        for (SpacePermission.MemberRule rule : s.getPermission().getMemberRules().values()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("Id", rule.id());
            e.putString("Role", rule.role().name());
            if (rule.overrideLevel() != null) e.putString("OverrideLevel", rule.overrideLevel().name());
            members.add(e);
        }
        t.put("Members", members);
        return t;
    }

    private static SpaceData deserialize(CompoundTag t) {
        try {
            UUID sid = t.getUUID("SpaceId"), oid = t.getUUID("OwnerId");
            // 始终用 spaceId 派生 dimensionId，避免旧存档 dimensionId 与 spaceId 不匹配导致 dimensionIndex 失效
            ResourceLocation dimensionId = SpaceData.defaultDimensionId(sid);
            int w = t.getInt("Width"), h = t.getInt("Height"), d = t.getInt("Depth");
            boolean mob = t.getBoolean("MobSpawning"), struct = t.getBoolean("StructureGeneration");
            boolean infinite = t.getBoolean("Infinite");
            float amplitude = t.contains("TerrainAmplitude") ? t.getFloat("TerrainAmplitude") : 0.4f;
            SpaceData.TerrainType terrain;
            if (t.contains("TerrainType")) terrain = SpaceData.TerrainType.valueOf(t.getString("TerrainType"));
            else if (t.contains("Flat")) terrain = t.getBoolean("Flat") ? SpaceData.TerrainType.SUPERFLAT : SpaceData.TerrainType.FLAT;
            else terrain = SpaceData.TerrainType.SUPERFLAT;
            String biome = t.contains("Biome") ? t.getString("Biome") : "minecraft:plains";

            ResourceLocation sourceDim = t.contains("SourceDimension")
                    ? ResourceLocation.parse(t.getString("SourceDimension"))
                    : ResourceLocation.parse("minecraft:overworld");

            SpaceData sd = new SpaceData(sid, oid, dimensionId, w, h, d, terrain, biome, sourceDim, mob, struct, infinite, amplitude);
            if (t.contains("Name")) sd.setName(t.getString("Name"));
            loadPermission(t, sd.getPermission());
            sd.setOfflineSimulationEnabled(t.getBoolean("OfflineSimulationEnabled"));
            return sd;
        } catch (Exception e) { PocketHomestead.LOGGER.error("反序列化空间数据失败", e); return null; }
    }

    // 读取权限：优先新格式（PermMode+Members），否则迁移旧格式（AccessMode+Whitelist/Blacklist）
    private static void loadPermission(CompoundTag t, SpacePermission perm) {
        if (t.contains("PermMode")) {
            perm.setMode(SpacePermission.AccessMode.valueOf(t.getString("PermMode")));
            if (t.contains("ProtectedLevel")) {
                perm.setProtectedLevel(SpacePermission.AccessLevel.valueOf(t.getString("ProtectedLevel")));
            }
            if (t.contains("PublicLevel")) {
                perm.setPublicLevel(SpacePermission.AccessLevel.valueOf(t.getString("PublicLevel")));
            }
            ListTag members = t.getList("Members", ListTag.TAG_COMPOUND);
            for (int i = 0; i < members.size(); i++) {
                CompoundTag member = members.getCompound(i);
                UUID id = member.getUUID("Id");
                SpacePermission.MemberRole role = member.contains("Role")
                        ? SpacePermission.MemberRole.valueOf(member.getString("Role"))
                        : SpacePermission.MemberRole.MEMBER;
                SpacePermission.AccessLevel override = member.contains("OverrideLevel")
                        ? SpacePermission.AccessLevel.valueOf(member.getString("OverrideLevel"))
                        : null;
                perm.setMember(id, role, override);
            }
            return;
        }
        // 旧格式迁移
        ListTag wl = t.getList("Whitelist", ListTag.TAG_COMPOUND);
        ListTag bl = t.getList("Blacklist", ListTag.TAG_COMPOUND);
        String oldMode = t.contains("AccessMode") ? t.getString("AccessMode") : "PRIVATE";
        if (oldMode.equals("PRIVATE")) {
            perm.setMode(SpacePermission.AccessMode.PRIVATE);
        } else if (!bl.isEmpty()) {
            perm.setMode(SpacePermission.AccessMode.BLACKLIST);
            for (int i = 0; i < bl.size(); i++) {
                perm.setMember(bl.getCompound(i).getUUID("Id"), SpacePermission.MemberRole.BLOCKED, SpacePermission.AccessLevel.NONE);
            }
        } else if (!wl.isEmpty()) {
            perm.setMode(SpacePermission.AccessMode.WHITELIST);
            for (int i = 0; i < wl.size(); i++) perm.addMember(wl.getCompound(i).getUUID("Id"));
        } else {
            perm.setMode(SpacePermission.AccessMode.PUBLIC);
        }
    }

    public static SavedData.Factory<SpaceStorage> factory() { return new SavedData.Factory<>(SpaceStorage::new, SpaceStorage::load); }
}
