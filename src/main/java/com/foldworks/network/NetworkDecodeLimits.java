package com.foldworks.network;

final class NetworkDecodeLimits {
    private NetworkDecodeLimits() {
    }

    static int checkedCount(int count, int max, String field) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException(field + " count out of bounds: " + count + " (max " + max + ")");
        }
        return count;
    }
}
