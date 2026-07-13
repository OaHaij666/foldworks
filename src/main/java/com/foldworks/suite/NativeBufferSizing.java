package com.foldworks.suite;

final class NativeBufferSizing {
    static final int MAX_CAPACITY = 16 * 1024 * 1024;

    private NativeBufferSizing() {
    }

    static int capacityFor(int requiredCapacity, int minimumCapacity) {
        if (requiredCapacity < 0 || minimumCapacity <= 0 || requiredCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("native planner buffer exceeds safety limit: " + requiredCapacity);
        }
        int capacity = Math.max(requiredCapacity, minimumCapacity);
        if (capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("native planner minimum buffer exceeds safety limit: " + minimumCapacity);
        }
        if (capacity == 1) return 1;
        return Integer.highestOneBit(capacity - 1) << 1;
    }
}
