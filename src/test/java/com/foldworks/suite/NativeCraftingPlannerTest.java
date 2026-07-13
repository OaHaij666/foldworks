package com.foldworks.suite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeCraftingPlannerTest {
    @Test
    void roundsBufferCapacityUpAndHonorsMinimum() {
        assertEquals(1, NativeBufferSizing.capacityFor(0, 1));
        assertEquals(8192, NativeBufferSizing.capacityFor(1, 8192));
        assertEquals(16384, NativeBufferSizing.capacityFor(8193, 8192));
    }

    @Test
    void rejectsNegativeAndExcessiveBufferRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> NativeBufferSizing.capacityFor(-1, 8192));
        assertThrows(IllegalArgumentException.class,
                () -> NativeBufferSizing.capacityFor(NativeBufferSizing.MAX_CAPACITY + 1, 8192));
    }
}
