package com.pockethomestead.archive;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import com.pockethomestead.transfer.GraphKey;
import com.pockethomestead.transfer.TransferEdge;
import com.pockethomestead.transfer.TransferGraph;
import com.pockethomestead.transfer.TransferGraphPage;
import com.pockethomestead.transfer.TransferGraphStorage;
import com.pockethomestead.transfer.TransferNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class SpaceArchiveService {
    public static final String EXTENSION = ".phspace";
    private static final String FORMAT_VERSION = "1";
    private static final String MANIFEST = "manifest.properties";
    private static final String SPACE_NBT = "space.nbt";
    private static final String GRAPH_NBT = "graph.nbt";
    private static final String DIMENSION_DIR = "dimension/";
    private static final String CHEST_BE_ID = PocketHomestead.MODID + ":homestead_chest";
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    // Zip Bomb 防护：限制解压后总字节数、entry 数量、单 entry 大小
    private static final long MAX_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024; // 1GB
    private static final int MAX_ZIP_ENTRIES = 10000;
    private static final long MAX_SINGLE_ENTRY_BYTES = 512L * 1024 * 1024; // 512MB
    // NbtAccounter 限制：解析 NBT 时最大堆使用，超过则抛异常而非 OOM
    private static final long NBT_HEAP_LIMIT = 512L * 1024 * 1024; // 512MB

    private SpaceArchiveService() {
    }

    public record ImportResult(UUID spaceId, String name, int remappedChests, int importedNodes) {}

    public static Path exportSpace(MinecraftServer server, SpaceData space, UUID requester) throws IOException {
        if (server == null || space == null) throw new IOException("空间不存在");
        server.saveEverything(false, true, true);

        Path dimensionPath = dimensionPath(server, space.getDimensionId());
        if (!Files.isDirectory(dimensionPath)) {
            throw new IOException("空间维度目录不存在: " + dimensionPath);
        }

        Path exportDir = archiveWorkDir(server).resolve("exports");
        Files.createDirectories(exportDir);
        String safeName = sanitizeFileName(space.getName()) + "-" + space.getSpaceId().toString().substring(0, 8) + EXTENSION;
        Path archive = exportDir.resolve(safeName);
        Path tmp = exportDir.resolve(safeName + ".tmp");
        Files.deleteIfExists(tmp);

        Properties manifest = new Properties();
        manifest.setProperty("format", FORMAT_VERSION);
        manifest.setProperty("createdAt", Instant.now().toString());
        manifest.setProperty("spaceId", space.getSpaceId().toString());
        manifest.setProperty("ownerId", space.getOwnerId().toString());
        manifest.setProperty("dimensionId", space.getDimensionId().toString());
        manifest.setProperty("name", space.getName());
        manifest.setProperty("experimental", "true");
        manifest.setProperty("warning", "Experimental feature. Space archives may be incomplete or incompatible.");
        if (requester != null) manifest.setProperty("exportedBy", requester.toString());

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            zip.putNextEntry(new ZipEntry(MANIFEST));
            manifest.store(zip, "Pocket Homestead experimental space archive");
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(SPACE_NBT));
            NbtIo.writeCompressed(spaceTag(space), zip);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(GRAPH_NBT));
            NbtIo.writeCompressed(exportSpaceGraph(server, space), zip);
            zip.closeEntry();

            zipDirectory(zip, dimensionPath, DIMENSION_DIR);
        }
        Files.move(tmp, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return archive;
    }

    public static ImportResult importArchive(MinecraftServer server, Path archive, UUID targetOwner) throws IOException {
        if (server == null || archive == null || targetOwner == null) throw new IOException("导入参数无效");
        if (!Files.isRegularFile(archive)) throw new IOException("空间包不存在");

        Path work = archiveWorkDir(server).resolve("imports").resolve(UUID.randomUUID().toString());
        Files.createDirectories(work);
        try {
            unzip(archive, work);
            Properties manifest = readManifest(work.resolve(MANIFEST));
            String archiveVersion = manifest.getProperty("format", "0");
            // 仅比较主版本号，兼容未来 1.1 等小版本升级
            if (!majorVersion(archiveVersion).equals(majorVersion(FORMAT_VERSION))) {
                throw new IOException("不支持的空间包版本: " + archiveVersion + "，当前支持主版本 " + majorVersion(FORMAT_VERSION));
            }
            CompoundTag originalSpaceTag = NbtIo.readCompressed(work.resolve(SPACE_NBT), NbtAccounter.create(NBT_HEAP_LIMIT));
            SpaceData original = readSpace(originalSpaceTag);
            UUID newSpaceId = UUID.randomUUID();
            ResourceLocation newDimension = SpaceData.defaultDimensionId(newSpaceId);
            // 夹紧尺寸到安全范围，防止恶意 archive 设置超大尺寸导致 PocketChunkGenerator 边界墙循环崩服
            int clampedWidth = Math.max(16, Math.min(original.getWidth(), 512));
            int clampedHeight = Math.max(16, Math.min(original.getHeight(), 512));
            int clampedDepth = Math.max(16, Math.min(original.getDepth(), 512));
            SpaceData imported = new SpaceData(
                    newSpaceId,
                    targetOwner,
                    newDimension,
                    clampedWidth,
                    clampedHeight,
                    clampedDepth,
                    original.getTerrainType(),
                    original.getBiome(),
                    original.getSourceDimension(),
                    original.isMobSpawning(),
                    original.isStructureGeneration(),
                    original.isInfinite(),
                    original.getTerrainAmplitude()
            );
            imported.setName(uniqueImportedName(original.getName()));
            imported.getPermission().setMode(SpacePermission.AccessMode.PRIVATE);
            imported.setOfflineSimulationEnabled(false);

            Path sourceDim = work.resolve("dimension");
            if (!Files.isDirectory(sourceDim)) throw new IOException("空间包缺少维度数据");
            Path targetDim = dimensionPath(server, newDimension);
            if (Files.exists(targetDim)) throw new IOException("目标维度目录已存在: " + targetDim);
            copyDirectory(sourceDim, targetDim);

            Map<String, String> chestIdMap = new LinkedHashMap<>();
            int remappedChests = remapChestsInRegions(targetDim, original.getDimensionId(), newDimension, targetOwner, newSpaceId, chestIdMap);

            SpaceManager.getInstance().addImportedSpace(server, imported);
            int importedNodes = importGraphSubset(server, work.resolve(GRAPH_NBT), original.getDimensionId(), newDimension, newSpaceId, chestIdMap);
            return new ImportResult(imported.getSpaceId(), imported.getName(), remappedChests, importedNodes);
        } finally {
            deleteDirectory(work);
        }
    }

    public static Path archiveWorkDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("pockethomestead").resolve("archives");
    }

    public static Path dimensionPath(MinecraftServer server, ResourceLocation dimensionId) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("dimensions")
                .resolve(dimensionId.getNamespace())
                .resolve(dimensionId.getPath());
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(path)) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IOException("无法计算空间包校验值", e);
        }
    }

    private static CompoundTag spaceTag(SpaceData space) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("SpaceId", space.getSpaceId());
        tag.putUUID("OwnerId", space.getOwnerId());
        tag.putString("DimensionId", space.getDimensionId().toString());
        tag.putInt("Width", space.getWidth());
        tag.putInt("Height", space.getHeight());
        tag.putInt("Depth", space.getDepth());
        tag.putString("TerrainType", space.getTerrainType().name());
        tag.putString("Biome", space.getBiome());
        if (space.getSourceDimension() != null) tag.putString("SourceDimension", space.getSourceDimension().toString());
        tag.putBoolean("MobSpawning", space.isMobSpawning());
        tag.putBoolean("StructureGeneration", space.isStructureGeneration());
        tag.putBoolean("Infinite", space.isInfinite());
        tag.putFloat("TerrainAmplitude", space.getTerrainAmplitude());
        tag.putString("Name", space.getName());
        return tag;
    }

    private static SpaceData readSpace(CompoundTag tag) throws IOException {
        UUID spaceId = tag.getUUID("SpaceId");
        UUID ownerId = tag.getUUID("OwnerId");
        ResourceLocation dimension = ResourceLocation.parse(tag.getString("DimensionId"));
        SpaceData.TerrainType terrain;
        try {
            terrain = SpaceData.TerrainType.valueOf(tag.getString("TerrainType"));
        } catch (IllegalArgumentException e) {
            throw new IOException("无法识别的地形类型: " + tag.getString("TerrainType"), e);
        }
        ResourceLocation source = tag.contains("SourceDimension")
                ? ResourceLocation.parse(tag.getString("SourceDimension"))
                : ResourceLocation.parse("minecraft:overworld");
        SpaceData space = new SpaceData(
                spaceId,
                ownerId,
                dimension,
                tag.getInt("Width"),
                tag.getInt("Height"),
                tag.getInt("Depth"),
                terrain,
                tag.getString("Biome"),
                source,
                tag.getBoolean("MobSpawning"),
                tag.getBoolean("StructureGeneration"),
                tag.getBoolean("Infinite"),
                tag.contains("TerrainAmplitude") ? tag.getFloat("TerrainAmplitude") : 0.4f
        );
        if (tag.contains("Name")) space.setName(tag.getString("Name"));
        return space;
    }

    private static CompoundTag exportSpaceGraph(MinecraftServer server, SpaceData space) {
        CompoundTag out = new CompoundTag();
        String dimension = space.getDimensionId().toString();
        ListTag pages = new ListTag();
        ListTag nodes = new ListTag();
        ListTag edges = new ListTag();
        TransferGraph graph = TransferGraphStorage.get(server).graphFor(GraphKey.spaceGraph(space.getSpaceId()));
        Set<String> include = new HashSet<>();
        Set<String> pageIds = new HashSet<>();
        for (TransferNode node : graph.getNodes()) {
            if (node.getNodeType() == TransferNode.NodeType.CHEST && dimension.equals(node.getDimensionKey())) {
                include.add(node.getId());
                pageIds.add(node.getPageId());
            } else if (node.getNodeType() == TransferNode.NodeType.REROUTE || node.getNodeType() == TransferNode.NodeType.TRASH) {
                include.add(node.getId());
                pageIds.add(node.getPageId());
            }
        }
        for (TransferGraphPage page : graph.getPages()) {
            if (pageIds.contains(page.getId())) pages.add(page.save());
        }
        for (TransferNode node : graph.getNodes()) {
            if (include.contains(node.getId())) nodes.add(node.save());
        }
        for (TransferEdge edge : graph.getEdges()) {
            if (include.contains(edge.getFromNodeId()) && include.contains(edge.getToNodeId())) {
                edges.add(edge.save());
            }
        }
        out.put("Pages", pages);
        out.put("Nodes", nodes);
        out.put("Edges", edges);
        return out;
    }

    private static int importGraphSubset(MinecraftServer server, Path graphPath, ResourceLocation oldDimension,
                                         ResourceLocation newDimension, UUID newSpaceId,
                                         Map<String, String> chestIdMap) throws IOException {
        if (!Files.isRegularFile(graphPath)) return 0;
        CompoundTag graphTag = NbtIo.readCompressed(graphPath, NbtAccounter.create(NBT_HEAP_LIMIT));
        ListTag oldPages = graphTag.getList("Pages", Tag.TAG_COMPOUND);
        ListTag oldNodes = graphTag.getList("Nodes", Tag.TAG_COMPOUND);
        ListTag oldEdges = graphTag.getList("Edges", Tag.TAG_COMPOUND);
        if (oldNodes.isEmpty()) return 0;

        TransferGraph target = TransferGraphStorage.get(server).graphFor(GraphKey.spaceGraph(newSpaceId));
        target.clearAll();
        Map<String, String> pageMap = new HashMap<>();
        Map<String, String> nodeMap = new HashMap<>();
        for (int i = 0; i < oldPages.size(); i++) {
            TransferGraphPage old = TransferGraphPage.load(oldPages.getCompound(i));
            String newId = UUID.randomUUID().toString();
            pageMap.put(old.getId(), newId);
            target.putPage(new TransferGraphPage(newId, old.getName(), old.isEnabled(), target.getPages().size()));
        }
        String fallbackPage = target.getPages().isEmpty()
                ? target.ensureDefaultPage().getId()
                : target.getPages().iterator().next().getId();

        for (int i = 0; i < oldNodes.size(); i++) {
            TransferNode old = TransferNode.load(oldNodes.getCompound(i), fallbackPage);
            if (old == null || old.getNodeType() == TransferNode.NodeType.PLAYER_INVENTORY) continue;
            String newPage = pageMap.getOrDefault(old.getPageId(), fallbackPage);
            TransferNode copy;
            if (old.getNodeType() == TransferNode.NodeType.CHEST) {
                if (!oldDimension.toString().equals(old.getDimensionKey())) continue;
                String key = chestRef(oldDimension, old.getPos(), old.getChestId());
                String newChestId = chestIdMap.get(key);
                if (newChestId == null || newChestId.isBlank()) continue;
                copy = new TransferNode(UUID.randomUUID().toString(), newPage, TransferNode.NodeType.CHEST,
                        newChestId, newDimension.toString(), old.getPos(), old.getX(), old.getY(),
                        old.isExpanded(), old.isEnabled(), old.getFilterItemIds(), old.getReceiveFilterIds(), null, List.of());
            } else {
                copy = new TransferNode(UUID.randomUUID().toString(), newPage, old.getNodeType(),
                        "", "", BlockPos.ZERO, old.getX(), old.getY(),
                        old.isExpanded(), old.isEnabled(), old.getFilterItemIds(), old.getReceiveFilterIds(), null, List.of());
            }
            nodeMap.put(old.getId(), copy.getId());
            target.putNode(copy);
        }

        int importedEdges = 0;
        for (int i = 0; i < oldEdges.size(); i++) {
            TransferEdge old = TransferEdge.load(oldEdges.getCompound(i), fallbackPage);
            String from = nodeMap.get(old.getFromNodeId());
            String to = nodeMap.get(old.getToNodeId());
            if (from == null || to == null) continue;
            TransferNode fromNode = target.getNode(from);
            if (fromNode == null) continue;
            TransferEdge copy = new TransferEdge(UUID.randomUUID().toString(), fromNode.getPageId(), from, to,
                    old.getFromPortKey(), old.getToPortKey(), false, 1, 64, old.isEnabled());
            for (TransferEdge.ItemRateSnapshot row : old.getItemRates()) {
                if (row.configured()) copy.setItemRate(row.itemId(), row.rateLimitEnabled(), row.rateLimitSeconds(), row.rateLimitItems());
            }
            target.putEdge(copy);
            importedEdges++;
        }
        TransferGraphStorage.get(server).setDirty();
        return nodeMap.size() + importedEdges;
    }

    private static int remapChestsInRegions(Path dimensionPath, ResourceLocation oldDimension, ResourceLocation newDimension,
                                            UUID targetOwner, UUID newSpaceId, Map<String, String> chestIdMap) throws IOException {
        Path regionDir = dimensionPath.resolve("region");
        if (!Files.isDirectory(regionDir)) return 0;
        int changed = 0;
        try {
            Constructor<RegionFileStorage> ctor = RegionFileStorage.class
                    .getDeclaredConstructor(RegionStorageInfo.class, Path.class, boolean.class);
            ctor.setAccessible(true);
            Method write = RegionFileStorage.class.getDeclaredMethod("write", ChunkPos.class, CompoundTag.class);
            write.setAccessible(true);
            RegionStorageInfo info = new RegionStorageInfo(
                    "pockethomestead_archive",
                    ResourceKey.create(Registries.DIMENSION, newDimension),
                    "chunk"
            );
            try (RegionFileStorage storage = ctor.newInstance(info, regionDir, true)) {
                List<Path> regions;
                try (var stream = Files.list(regionDir)) {
                    regions = stream.filter(path -> path.getFileName().toString().endsWith(".mca")).toList();
                }
                int[] counter = {1};
                for (Path region : regions) {
                    Matcher matcher = REGION_NAME.matcher(region.getFileName().toString());
                    if (!matcher.matches()) continue;
                    int rx = Integer.parseInt(matcher.group(1));
                    int rz = Integer.parseInt(matcher.group(2));
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            ChunkPos pos = new ChunkPos(rx * 32 + lx, rz * 32 + lz);
                            CompoundTag chunk = storage.read(pos);
                            if (chunk == null) continue;
                            if (remapChunkChests(chunk, oldDimension, targetOwner, newSpaceId, chestIdMap, counter)) {
                                write.invoke(storage, pos, chunk);
                                changed++;
                            }
                        }
                    }
                }
                storage.flush();
            }
            return changed;
        } catch (Exception e) {
            throw new IOException("无法重写空间包内箱子归属，请不要导入该空间包", e);
        }
    }

    private static boolean remapChunkChests(CompoundTag chunk, ResourceLocation oldDimension, UUID targetOwner,
                                            UUID newSpaceId, Map<String, String> chestIdMap, int[] counter) {
        boolean changed = false;
        changed |= remapBlockEntityList(chunk.getList("block_entities", Tag.TAG_COMPOUND), oldDimension, targetOwner, newSpaceId, chestIdMap, counter);
        if (chunk.contains("Level", Tag.TAG_COMPOUND)) {
            CompoundTag level = chunk.getCompound("Level");
            changed |= remapBlockEntityList(level.getList("TileEntities", Tag.TAG_COMPOUND), oldDimension, targetOwner, newSpaceId, chestIdMap, counter);
        }
        return changed;
    }

    private static boolean remapBlockEntityList(ListTag list, ResourceLocation oldDimension, UUID targetOwner,
                                                UUID newSpaceId, Map<String, String> chestIdMap, int[] counter) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag be = list.getCompound(i);
            if (!CHEST_BE_ID.equals(be.getString("id"))) continue;
            String oldChestId = be.getString("ChestId");
            BlockPos pos = new BlockPos(be.getInt("x"), be.getInt("y"), be.getInt("z"));
            String key = chestRef(oldDimension, pos, oldChestId);
            String newChestId = chestIdMap.computeIfAbsent(key, ignored ->
                    "import_" + newSpaceId.toString().substring(0, 8) + "_" + counter[0]++);
            be.putUUID("Owner", targetOwner);
            be.putString("ChestId", newChestId);
            be.putString("GraphKind", GraphKey.Kind.SPACE.name());
            be.remove("GraphTeamId");
            changed = true;
        }
        return changed;
    }

    private static String chestRef(ResourceLocation dimension, BlockPos pos, String chestId) {
        return dimension + "|" + pos.asLong() + "|" + (chestId == null ? "" : chestId);
    }

    private static void zipDirectory(ZipOutputStream zip, Path source, String prefix) throws IOException {
        try (var stream = Files.walk(source)) {
            List<Path> paths = stream.sorted().toList();
            for (Path path : paths) {
                if (Files.isDirectory(path)) continue;
                String rel = source.relativize(path).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(prefix + rel));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private static void unzip(Path archive, Path target) throws IOException {
        long totalUncompressed = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IOException("空间包条目过多（>" + MAX_ZIP_ENTRIES + "），疑似 Zip Bomb");
                }
                Path out = target.resolve(entry.getName()).normalize();
                if (!out.startsWith(target)) throw new IOException("空间包路径非法: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream output = Files.newOutputStream(out)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        long entryTotal = 0;
                        while ((read = zip.read(buffer)) >= 0) {
                            if (read == 0) continue;
                            output.write(buffer, 0, read);
                            entryTotal += read;
                            totalUncompressed += read;
                            if (entryTotal > MAX_SINGLE_ENTRY_BYTES) {
                                throw new IOException("空间包单条目过大（>" + MAX_SINGLE_ENTRY_BYTES + "）: " + entry.getName());
                            }
                            if (totalUncompressed > MAX_UNCOMPRESSED_BYTES) {
                                throw new IOException("空间包解压后总字节超过限制（>" + MAX_UNCOMPRESSED_BYTES + "），疑似 Zip Bomb");
                            }
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static Properties readManifest(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path out = target.resolve(source.relativize(path).toString()).normalize();
                if (!out.startsWith(target)) throw new IOException("空间目录路径非法");
                if (Files.isDirectory(path)) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(path, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteDirectory(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            PocketHomestead.LOGGER.warn("清理空间迁移临时目录失败: {}", path, e);
        }
    }

    private static String sanitizeFileName(String value) {
        // 过滤路径危险字符与控制字符（U+0000-U+001F、U+007F-U+009F），保留中文等正常 Unicode
        String name = value == null || value.isBlank() ? "space" : value.trim();
        name = name.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]+", "_");
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) name = "space";
        return name;
    }

    private static String uniqueImportedName(String base) {
        String clean = base == null || base.isBlank() ? "导入空间" : base.trim();
        // 用代码点截断避免在 Unicode 代理对中间切断导致乱码
        int[] cps = clean.codePoints().limit(20).toArray();
        return new String(cps, 0, cps.length) + "-导入";
    }

    private static String majorVersion(String version) {
        if (version == null) return "0";
        int dot = version.indexOf('.');
        return dot < 0 ? version : version.substring(0, dot);
    }
}
