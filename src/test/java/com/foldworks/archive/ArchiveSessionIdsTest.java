package com.foldworks.archive;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveSessionIdsTest {
    @Test
    void acceptsCanonicalUuidAndKeepsUploadInsideStaging() {
        String id = UUID.randomUUID().toString();
        Path root = Path.of("build", "archive-test", "staging").toAbsolutePath().normalize();
        Path result = ArchiveSessionIds.uploadPath(root, id, ".phspace");

        assertTrue(result.startsWith(root));
        assertEquals(id + ".phspace.upload", result.getFileName().toString());
    }

    @Test
    void rejectsTraversalAndAbsolutePaths() {
        assertThrows(IllegalArgumentException.class,
                () -> ArchiveSessionIds.uploadPath(Path.of("staging"), "../outside", ".phspace"));
        assertThrows(IllegalArgumentException.class,
                () -> ArchiveSessionIds.uploadPath(Path.of("staging"), Path.of("C:/outside").toString(), ".phspace"));
    }

    @Test
    void rejectsNullBlankAndNonCanonicalUuid() {
        assertThrows(IllegalArgumentException.class, () -> ArchiveSessionIds.requireCanonicalUuid(null));
        assertThrows(IllegalArgumentException.class, () -> ArchiveSessionIds.requireCanonicalUuid(""));
        assertThrows(IllegalArgumentException.class,
                () -> ArchiveSessionIds.requireCanonicalUuid("00000000-0000-0000-0000-00000000000A"));
    }
}
