package com.foldworks.transfer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lazily indexes graph edges by source node while preserving insertion order.
 * Mutating callers invalidate the index; readers receive a defensive list.
 */
final class OutgoingEdgeIndex<T> {
    private final Map<String, List<T>> bySource = new LinkedHashMap<>();
    private boolean dirty = true;

    void invalidate() {
        dirty = true;
    }

    List<T> get(String sourceId, Collection<T> values, Function<T, String> sourceKey) {
        if (dirty) rebuild(values, sourceKey);
        return new ArrayList<>(bySource.getOrDefault(sourceId, List.of()));
    }

    private void rebuild(Collection<T> values, Function<T, String> sourceKey) {
        bySource.clear();
        for (T value : values) {
            bySource.computeIfAbsent(sourceKey.apply(value), ignored -> new ArrayList<>()).add(value);
        }
        dirty = false;
    }
}
