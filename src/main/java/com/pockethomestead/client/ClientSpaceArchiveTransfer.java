package com.pockethomestead.client;

import com.pockethomestead.archive.SpaceArchiveService;
import com.pockethomestead.config.ModConfig;
import com.pockethomestead.network.SpaceArchiveClientChunkPacket;
import com.pockethomestead.network.SpaceArchiveRequestPacket;
import com.pockethomestead.network.SpaceArchiveServerPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ClientSpaceArchiveTransfer {
    private static String status = "空间迁移是实验性功能，不稳定，请谨慎使用";
    private static Upload upload;
    private static Download download;

    private ClientSpaceArchiveTransfer() {
    }

    /** 每客户端 tick 上传分块大小：取配置分块大小与每秒限速/20 的较小值，保证不超限速。 */
    private static int uploadChunkBytes() {
        int chunk = ModConfig.SPACE_ARCHIVE_CHUNK_BYTES.get();
        int perTick = Math.max(4096, ModConfig.SPACE_ARCHIVE_BYTES_PER_SECOND_PER_PLAYER.get() / 20);
        return Math.min(chunk, perTick);
    }

    public static Path archiveDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("pockethomestead").resolve("archives");
    }

    public static List<Path> localArchives() {
        try {
            Files.createDirectories(archiveDir());
            try (var stream = Files.list(archiveDir())) {
                return stream
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(SpaceArchiveService.EXTENSION))
                        .sorted(Comparator.comparingLong(ClientSpaceArchiveTransfer::modifiedTime).reversed())
                        .toList();
            }
        } catch (IOException e) {
            status = "读取本地空间包失败: " + e.getMessage();
            return List.of();
        }
    }

    public static void requestDownload(UUID spaceId) {
        if (spaceId == null) return;
        String sessionId = UUID.randomUUID().toString();
        status = "已请求服务器导出空间...";
        PacketDistributor.sendToServer(SpaceArchiveRequestPacket.download(spaceId, sessionId));
    }

    public static void requestUpload(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) {
            status = "请选择一个本地 .phspace 空间包";
            return;
        }
        try {
            String sessionId = UUID.randomUUID().toString();
            long size = Files.size(archive);
            String sha = sha256(archive);
            upload = new Upload(sessionId, archive, archive.getFileName().toString(), size, sha);
            status = "正在请求上传空间包...";
            PacketDistributor.sendToServer(SpaceArchiveRequestPacket.uploadBegin(sessionId, upload.fileName, size, sha));
        } catch (Exception e) {
            upload = null;
            status = "准备上传失败: " + e.getMessage();
        }
    }

    public static void tickUpload() {
        if (upload == null || !upload.ready) return;
        try {
            if (upload.input == null) upload.input = Files.newInputStream(upload.path);
            byte[] data = upload.input.readNBytes(upload.chunkBytes);
            if (data.length <= 0) return;
            PacketDistributor.sendToServer(new SpaceArchiveClientChunkPacket(upload.sessionId, upload.index, upload.totalChunks, data));
            upload.index++;
            status = "上传中 " + Math.min(100, (int) Math.round(upload.index * 100.0 / upload.totalChunks)) + "%";
            if (upload.index >= upload.totalChunks) {
                upload.close();
                upload.ready = false;
                status = "上传完成，等待服务器导入...";
            }
        } catch (Exception e) {
            if (upload != null) upload.close();
            upload = null;
            status = "上传失败: " + e.getMessage();
        }
    }

    public static void handleServerPacket(SpaceArchiveServerPacket packet) {
        switch (packet.action()) {
            case "UPLOAD_READY" -> {
                if (upload != null && upload.sessionId.equals(packet.sessionId())) {
                    upload.ready = true;
                    status = "服务器已接受，开始上传...";
                }
            }
            case "UPLOAD_DONE" -> {
                if (upload != null) upload.close();
                upload = null;
                status = packet.message();
            }
            case "DOWNLOAD_BEGIN" -> beginDownload(packet);
            case "DOWNLOAD_CHUNK" -> writeDownloadChunk(packet);
            case "DOWNLOAD_END" -> finishDownload(packet);
            case "ERROR" -> {
                if (upload != null) upload.close();
                upload = null;
                if (download != null) download.close();
                download = null;
                status = packet.message();
            }
            default -> {
                if (packet.message() != null && !packet.message().isBlank()) status = packet.message();
            }
        }
    }

    public static String status() {
        return status;
    }

    private static void beginDownload(SpaceArchiveServerPacket packet) {
        try {
            Files.createDirectories(archiveDir());
            Path target = archiveDir().resolve(sanitize(packet.fileName()));
            Path tmp = archiveDir().resolve(sanitize(packet.fileName()) + ".download");
            Files.deleteIfExists(tmp);
            download = new Download(packet.sessionId(), target, tmp, packet.totalChunks(), packet.totalBytes());
            status = "下载空间包中 0%";
        } catch (IOException e) {
            download = null;
            status = "创建下载文件失败: " + e.getMessage();
        }
    }

    private static void writeDownloadChunk(SpaceArchiveServerPacket packet) {
        if (download == null || !download.sessionId.equals(packet.sessionId())) return;
        try {
            if (packet.index() != download.nextIndex) {
                status = "下载分块顺序错误";
                download.close();
                download = null;
                return;
            }
            Files.write(download.tmp, packet.data(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            download.nextIndex++;
            status = "下载空间包中 " + Math.min(100, (int) Math.round(download.nextIndex * 100.0 / Math.max(1, download.totalChunks))) + "%";
        } catch (IOException e) {
            download.close();
            download = null;
            status = "写入下载文件失败: " + e.getMessage();
        }
    }

    private static void finishDownload(SpaceArchiveServerPacket packet) {
        if (download == null || !download.sessionId.equals(packet.sessionId())) {
            status = packet.message();
            return;
        }
        try {
            Files.move(download.tmp, download.target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            status = "已保存到 " + download.target;
        } catch (IOException e) {
            status = "保存下载文件失败: " + e.getMessage();
        } finally {
            download = null;
        }
    }

    private static long modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
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
            throw new IOException("无法计算校验值", e);
        }
    }

    private static String sanitize(String value) {
        String name = value == null || value.isBlank() ? "space" + SpaceArchiveService.EXTENSION : value;
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return name.endsWith(SpaceArchiveService.EXTENSION) ? name : name + SpaceArchiveService.EXTENSION;
    }

    private static final class Upload {
        private final String sessionId;
        private final Path path;
        private final String fileName;
        private final int totalChunks;
        private final int chunkBytes;
        private InputStream input;
        private int index;
        private boolean ready;

        private Upload(String sessionId, Path path, String fileName, long totalBytes, String sha) {
            this.sessionId = sessionId;
            this.path = path;
            this.fileName = fileName;
            this.chunkBytes = uploadChunkBytes();
            this.totalChunks = Math.max(1, (int) Math.ceil(totalBytes / (double) chunkBytes));
        }

        private void close() {
            if (input == null) return;
            try {
                input.close();
            } catch (IOException ignored) {
            }
            input = null;
        }
    }

    private static final class Download {
        private final String sessionId;
        private final Path target;
        private final Path tmp;
        private final int totalChunks;
        private final long totalBytes;
        private int nextIndex;

        private Download(String sessionId, Path target, Path tmp, int totalChunks, long totalBytes) {
            this.sessionId = sessionId;
            this.target = target;
            this.tmp = tmp;
            this.totalChunks = totalChunks;
            this.totalBytes = totalBytes;
        }

        private void close() {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }
}
