package com.pockethomestead.archive;

import com.pockethomestead.PocketHomestead;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.network.SpaceArchiveClientChunkPacket;
import com.pockethomestead.network.SpaceArchiveServerPacket;
import com.pockethomestead.network.SpaceListPayload;
import com.pockethomestead.space.SpaceData;
import com.pockethomestead.space.SpaceExperienceCost;
import com.pockethomestead.space.SpaceManager;
import com.pockethomestead.space.SpacePermission;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SpaceArchiveTransferManager {
    // 线程模型：UPLOADS/DOWNLOADS 仅在主线程访问（packet handler enqueueWork + server tick），LinkedHashMap 即可
    private static final Map<String, UploadSession> UPLOADS = new LinkedHashMap<>();
    private static final Map<String, DownloadSession> DOWNLOADS = new LinkedHashMap<>();
    // 上传会话超时：60 秒无分块则清理，避免玩家下线后 staging 文件泄漏 + 占用并发名额
    private static final int UPLOAD_TIMEOUT_TICKS = 60 * 20;

    private SpaceArchiveTransferManager() {
    }

    public static void reset() {
        for (UploadSession session : UPLOADS.values()) {
            try {
                Files.deleteIfExists(session.path);
            } catch (IOException ignored) {
            }
        }
        for (DownloadSession session : DOWNLOADS.values()) {
            session.closeAndDelete();
        }
        UPLOADS.clear();
        DOWNLOADS.clear();
    }

    public static void beginUpload(ServerPlayer player, String sessionId, String fileName, long totalBytes, String sha256,
                                   int archiveWidth, int archiveDepth, boolean archiveInfinite) {
        if (player == null || sessionId == null || sessionId.isBlank()) return;
        try {
            if (UPLOADS.size() + DOWNLOADS.size() >= ModConfig.SPACE_ARCHIVE_MAX_CONCURRENT_TRANSFERS.get()) {
                sendError(player, sessionId, "服务器正在处理其他空间包，请稍后再试");
                return;
            }
            if (totalBytes <= 0 || totalBytes > ModConfig.SPACE_ARCHIVE_MAX_BYTES.get()) {
                sendError(player, sessionId, "空间包过大或为空");
                return;
            }
            if (UPLOADS.containsKey(sessionId)) {
                sendError(player, sessionId, "上传会话重复");
                return;
            }
            if (archiveWidth <= 0 || archiveDepth <= 0) {
                sendError(player, sessionId, "空间包缺少尺寸信息，请重新导出后再上传");
                return;
            }
            int chargedWidth = Math.max(16, Math.min(archiveWidth, 512));
            int chargedDepth = Math.max(16, Math.min(archiveDepth, 512));
            int expCost = SpaceExperienceCost.chargeableCost(player, chargedWidth, chargedDepth, archiveInfinite);
            if (!SpaceExperienceCost.canAfford(player, expCost)) {
                sendError(player, sessionId, "经验不足，需要 " + expCost + " 点经验");
                return;
            }
            Path staging = SpaceArchiveService.archiveWorkDir(player.server)
                    .resolve("staging")
                    .resolve(sessionId + SpaceArchiveService.EXTENSION + ".upload");
            Files.createDirectories(staging.getParent());
            Files.deleteIfExists(staging);
            UPLOADS.put(sessionId, new UploadSession(player.getUUID(), staging, fileName, totalBytes, sha256,
                    player.server.overworld().getGameTime()));
            String message = expCost > 0 ? "准备上传，预计消耗经验 " + expCost : "准备上传";
            PacketDistributor.sendToPlayer(player, SpaceArchiveServerPacket.simple("UPLOAD_READY", sessionId, fileName, message));
        } catch (Exception e) {
            PocketHomestead.LOGGER.error("创建空间包上传会话失败", e);
            sendError(player, sessionId, "创建上传会话失败: " + e.getMessage());
        }
    }

    public static void receiveUploadChunk(ServerPlayer player, SpaceArchiveClientChunkPacket packet) {
        if (player == null || packet == null) return;
        UploadSession session = UPLOADS.get(packet.sessionId());
        if (session == null || !session.playerId.equals(player.getUUID())) {
            sendError(player, packet.sessionId(), "上传会话无效");
            return;
        }
        try {
            if (packet.index() != session.nextIndex) {
                failUpload(player, packet.sessionId(), "上传分块顺序错误");
                return;
            }
            if (packet.data().length > ModConfig.SPACE_ARCHIVE_CHUNK_BYTES.get()) {
                failUpload(player, packet.sessionId(), "上传分块超过服务器限制");
                return;
            }
            // 先校验累计字节上限，再写入文件；receivedBytes 已是权威计数，无需 Files.size 二次校验
            long next = session.receivedBytes + packet.data().length;
            if (next > session.totalBytes || next > ModConfig.SPACE_ARCHIVE_MAX_BYTES.get()) {
                failUpload(player, packet.sessionId(), "上传超过服务器限制");
                return;
            }
            Files.write(session.path, packet.data(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            session.receivedBytes = next;
            session.nextIndex++;
            session.lastActivityTick = player.server.overworld().getGameTime();
            if (session.receivedBytes == session.totalBytes) {
                finishUpload(player, packet.sessionId(), session);
            }
        } catch (Exception e) {
            PocketHomestead.LOGGER.error("处理空间包上传分块失败", e);
            failUpload(player, packet.sessionId(), "上传失败: " + e.getMessage());
        }
    }

    public static void beginDownload(ServerPlayer player, UUID spaceId, String sessionId) {
        if (player == null || spaceId == null || sessionId == null || sessionId.isBlank()) return;
        try {
            PocketHomestead.LOGGER.info("空间包下载请求: session={} player={} space={}",
                    shortSession(sessionId), player.getGameProfile().getName(), spaceId);
            if (UPLOADS.size() + DOWNLOADS.size() >= ModConfig.SPACE_ARCHIVE_MAX_CONCURRENT_TRANSFERS.get()) {
                PocketHomestead.LOGGER.warn("空间包下载拒绝: session={} 原因=并发达到上限 active={}",
                        shortSession(sessionId), UPLOADS.size() + DOWNLOADS.size());
                sendError(player, sessionId, "服务器正在处理其他空间包，请稍后再试");
                return;
            }
            SpaceData space = SpaceManager.getInstance().getSpace(spaceId);
            if (space == null) {
                PocketHomestead.LOGGER.warn("空间包下载拒绝: session={} 原因=空间不存在 space={}",
                        shortSession(sessionId), spaceId);
                sendError(player, sessionId, "空间不存在");
                return;
            }
            SpacePermission.AccessLevel required = downloadLevel();
            if (!space.can(player.getUUID(), required)) {
                PocketHomestead.LOGGER.warn("空间包下载拒绝: session={} 原因=权限不足 required={} player={} space={}",
                        shortSession(sessionId), required, player.getGameProfile().getName(), spaceId);
                sendError(player, sessionId, "权限不足，至少需要 " + required.name());
                return;
            }
            Path archive = SpaceArchiveService.exportSpace(player.server, space, player.getUUID());
            long size = Files.size(archive);
            if (size > ModConfig.SPACE_ARCHIVE_MAX_BYTES.get()) {
                Files.deleteIfExists(archive);
                PocketHomestead.LOGGER.warn("空间包下载拒绝: session={} 原因=空间包过大 size={} max={}",
                        shortSession(sessionId), size, ModConfig.SPACE_ARCHIVE_MAX_BYTES.get());
                sendError(player, sessionId, "空间包超过服务器限制");
                return;
            }
            DownloadSession download = new DownloadSession(player.getUUID(), archive, archive.getFileName().toString(), size);
            DOWNLOADS.put(sessionId, download);
            PocketHomestead.LOGGER.info("空间包下载开始: session={} file={} bytes={} chunks={} chunkSize={} path={}",
                    shortSession(sessionId), download.fileName, download.totalBytes, download.totalChunks(), download.chunkSize, archive);
            PacketDistributor.sendToPlayer(player, SpaceArchiveServerPacket.beginDownload(sessionId, download.fileName, download.totalBytes, download.totalChunks()));
        } catch (Exception e) {
            PocketHomestead.LOGGER.error("创建空间包下载失败", e);
            sendError(player, sessionId, "创建下载失败: " + e.getMessage());
        }
    }

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long currentTick = server.overworld().getGameTime();
        // 清理超时的上传会话：玩家下线或停止上传后 staging 文件会被回收
        if (!UPLOADS.isEmpty()) {
            Iterator<Map.Entry<String, UploadSession>> uploadIter = UPLOADS.entrySet().iterator();
            while (uploadIter.hasNext()) {
                Map.Entry<String, UploadSession> entry = uploadIter.next();
                UploadSession session = entry.getValue();
                if (currentTick - session.lastActivityTick > UPLOAD_TIMEOUT_TICKS) {
                    PocketHomestead.LOGGER.warn("上传会话 {} 超时未活动，清理", entry.getKey());
                    try {
                        Files.deleteIfExists(session.path);
                    } catch (IOException ignored) {
                    }
                    uploadIter.remove();
                }
            }
        }
        if (DOWNLOADS.isEmpty()) return;
        Iterator<Map.Entry<String, DownloadSession>> iterator = DOWNLOADS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, DownloadSession> entry = iterator.next();
            String sessionId = entry.getKey();
            DownloadSession session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId);
            if (player == null) {
                session.closeAndDelete();
                iterator.remove();
                continue;
            }
            try {
                int read = session.input.readNBytes(session.buffer, 0, session.chunkSize);
                if (read > 0) {
                    byte[] chunk = java.util.Arrays.copyOf(session.buffer, read);
                    PacketDistributor.sendToPlayer(player, SpaceArchiveServerPacket.downloadChunk(
                            sessionId, session.fileName, session.index, session.totalChunks(), session.totalBytes, chunk));
                    session.index++;
                    session.sentBytes += read;
                    if (session.index == 1 || session.index == session.totalChunks() || session.index % 40 == 0) {
                        PocketHomestead.LOGGER.info("空间包下载发送: session={} chunk={}/{} sent={}/{}",
                                shortSession(sessionId), session.index, session.totalChunks(), session.sentBytes, session.totalBytes);
                    }
                }
                if (read == 0 || session.sentBytes >= session.totalBytes) {
                    PocketHomestead.LOGGER.info("空间包下载发送完成: session={} file={} sent={}/{} chunks={}",
                            shortSession(sessionId), session.fileName, session.sentBytes, session.totalBytes, session.index);
                    PacketDistributor.sendToPlayer(player, SpaceArchiveServerPacket.simple("DOWNLOAD_END", sessionId, session.fileName, "下载完成"));
                    session.closeAndDelete();
                    iterator.remove();
                }
            } catch (Exception e) {
                PocketHomestead.LOGGER.error("推送空间包下载分块失败", e);
                sendError(player, sessionId, "下载失败: " + e.getMessage());
                session.closeAndDelete();
                iterator.remove();
            }
        }
    }

    private static void finishUpload(ServerPlayer player, String sessionId, UploadSession session) throws IOException {
        if (session.receivedBytes != session.totalBytes || Files.size(session.path) != session.totalBytes) {
            failUpload(player, sessionId, "上传大小不匹配");
            return;
        }
        String actualSha = SpaceArchiveService.sha256(session.path);
        if (session.sha256 != null && !session.sha256.isBlank() && !session.sha256.equalsIgnoreCase(actualSha)) {
            failUpload(player, sessionId, "上传校验失败");
            return;
        }
        SpaceArchiveService.ImportResult result = SpaceArchiveService.importArchive(player.server, session.path, player);
        UPLOADS.remove(sessionId);
        Files.deleteIfExists(session.path);
        String expText = result.chargedExperience() > 0 ? "，消耗经验 " + result.chargedExperience() : "";
        PacketDistributor.sendToPlayer(player, SpaceArchiveServerPacket.simple(
                "UPLOAD_DONE",
                sessionId,
                session.fileName,
                "已创建服务器空间 " + result.name() + "，箱子 " + result.remappedChests() + " 个，图元素 " + result.importedNodes() + " 个" + expText
        ));
        SpaceListPayload.sendToAll(player.server);
    }

    private static void failUpload(ServerPlayer player, String sessionId, String message) {
        UploadSession session = UPLOADS.remove(sessionId);
        if (session != null) {
            try {
                Files.deleteIfExists(session.path);
            } catch (IOException ignored) {
            }
        }
        sendError(player, sessionId, message);
    }

    private static void sendError(ServerPlayer player, String sessionId, String message) {
        PocketHomestead.LOGGER.warn("空间包传输错误: session={} player={} message={}",
                shortSession(sessionId),
                player == null ? "<null>" : player.getGameProfile().getName(),
                message);
        if (player != null) PacketDistributor.sendToPlayer(player, SpaceArchiveServerPacket.simple("ERROR", sessionId, "", message));
    }

    private static SpacePermission.AccessLevel downloadLevel() {
        try {
            return SpacePermission.AccessLevel.valueOf(ModConfig.SPACE_ARCHIVE_DOWNLOAD_MIN_LEVEL.get().trim().toUpperCase());
        } catch (Exception e) {
            return SpacePermission.AccessLevel.WRITE;
        }
    }

    private static String shortSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "<blank>";
        return sessionId.length() <= 8 ? sessionId : sessionId.substring(0, 8);
    }

    private static final class UploadSession {
        private final UUID playerId;
        private final Path path;
        private final String fileName;
        private final long totalBytes;
        private final String sha256;
        private int nextIndex;
        private long receivedBytes;
        private long lastActivityTick;

        private UploadSession(UUID playerId, Path path, String fileName, long totalBytes, String sha256, long startTick) {
            this.playerId = playerId;
            this.path = path;
            this.fileName = fileName == null || fileName.isBlank() ? "upload.phspace" : fileName;
            this.totalBytes = totalBytes;
            this.sha256 = sha256;
            this.lastActivityTick = startTick;
        }
    }

    private static final class DownloadSession {
        private final UUID playerId;
        private final Path path;
        private final String fileName;
        private final long totalBytes;
        private final InputStream input;
        private final int chunkSize;
        private final int totalChunks;
        private final byte[] buffer;
        private int index;
        private long sentBytes;

        private DownloadSession(UUID playerId, Path path, String fileName, long totalBytes) throws IOException {
            this.playerId = playerId;
            this.path = path;
            this.fileName = fileName;
            this.totalBytes = totalBytes;
            this.chunkSize = Math.min(ModConfig.SPACE_ARCHIVE_CHUNK_BYTES.get(),
                    Math.max(4096, ModConfig.SPACE_ARCHIVE_BYTES_PER_SECOND_PER_PLAYER.get() / 20));
            this.totalChunks = Math.max(1, (int) Math.ceil(totalBytes / (double) chunkSize));
            this.input = Files.newInputStream(path);
            this.buffer = new byte[chunkSize];
        }

        private int totalChunks() {
            return totalChunks;
        }

        private void closeAndDelete() {
            try {
                input.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
    }
}
