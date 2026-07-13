package com.foldworks.archive;

import java.nio.file.Path;
import java.util.UUID;

final class ArchiveSessionIds {
    private ArchiveSessionIds() {
    }

    static String requireCanonicalUuid(String value) {
        if (value == null) throw new IllegalArgumentException("sessionId is required");
        UUID parsed = UUID.fromString(value);
        String canonical = parsed.toString();
        if (!canonical.equals(value)) throw new IllegalArgumentException("sessionId must be a canonical UUID");
        return canonical;
    }

    static Path uploadPath(Path stagingRoot, String sessionId, String extension) {
        Path safeRoot = stagingRoot.toAbsolutePath().normalize();
        String safeId = requireCanonicalUuid(sessionId);
        Path candidate = safeRoot.resolve(safeId + extension + ".upload").normalize();
        if (!candidate.startsWith(safeRoot)) throw new IllegalArgumentException("session path escapes staging root");
        return candidate;
    }
}
