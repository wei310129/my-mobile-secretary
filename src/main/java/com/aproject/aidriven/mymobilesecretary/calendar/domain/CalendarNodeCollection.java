package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.util.List;
import java.util.Objects;

final class CalendarNodeCollection {

    private CalendarNodeCollection() {}

    static void add(List<CalendarTimeNode> nodes, CalendarTimeNode node) {
        Objects.requireNonNull(node, "node");
        if (nodes.stream().anyMatch(existing -> existing.id().equals(node.id()))) {
            throw new IllegalArgumentException("Node id already exists in this owner");
        }
        nodes.add(node);
    }
}
