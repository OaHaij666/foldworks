package com.foldworks.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkDecodeLimitsTest {
    @Test
    void acceptsBoundaryValues() {
        assertEquals(0, NetworkDecodeLimits.checkedCount(0, 64, "nodes"));
        assertEquals(64, NetworkDecodeLimits.checkedCount(64, 64, "nodes"));
    }

    @Test
    void rejectsNegativeAndOversizedCounts() {
        assertThrows(RuntimeException.class, () -> NetworkDecodeLimits.checkedCount(-1, 64, "nodes"));
        assertThrows(RuntimeException.class, () -> NetworkDecodeLimits.checkedCount(65, 64, "nodes"));
    }
}
