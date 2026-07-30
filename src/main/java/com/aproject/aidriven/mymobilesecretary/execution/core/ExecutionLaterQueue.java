package com.aproject.aidriven.mymobilesecretary.execution.core;

import java.util.List;
import java.util.Objects;

public record ExecutionLaterQueue(List<ExecutionRecommendationItem> orderedItems) {

    public ExecutionLaterQueue {
        Objects.requireNonNull(orderedItems, "orderedItems");
        orderedItems = List.copyOf(orderedItems);
    }

    public int count() {
        return orderedItems.size();
    }
}
