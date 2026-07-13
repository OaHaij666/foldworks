package com.foldworks.transfer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutgoingEdgeIndexTest {
    private record Edge(String id, String source) {}

    @Test
    void rebuildsOnlyAfterInvalidation() {
        OutgoingEdgeIndex<Edge> index = new OutgoingEdgeIndex<>();
        List<Edge> edges = new ArrayList<>();
        Edge first = new Edge("first", "a");
        Edge second = new Edge("second", "b");
        edges.add(first);

        assertEquals(List.of(first), index.get("a", edges, Edge::source));
        edges.add(second);
        assertTrue(index.get("b", edges, Edge::source).isEmpty());

        index.invalidate();
        assertEquals(List.of(second), index.get("b", edges, Edge::source));
    }

    @Test
    void returnsDefensiveListsInInsertionOrder() {
        OutgoingEdgeIndex<Edge> index = new OutgoingEdgeIndex<>();
        Edge first = new Edge("first", "a");
        Edge second = new Edge("second", "a");
        List<Edge> edges = List.of(first, second);

        List<Edge> callerCopy = index.get("a", edges, Edge::source);
        assertEquals(List.of(first, second), callerCopy);
        callerCopy.clear();

        assertEquals(List.of(first, second), index.get("a", edges, Edge::source));
    }
}
