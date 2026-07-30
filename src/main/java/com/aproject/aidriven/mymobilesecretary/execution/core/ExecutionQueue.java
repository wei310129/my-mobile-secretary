package com.aproject.aidriven.mymobilesecretary.execution.core;

import java.util.HashSet;
import java.util.Optional;

public record ExecutionQueue(
        Optional<ExecutionRecommendationItem> now,
        Optional<ExecutionRecommendationItem> next,
        ExecutionLaterQueue later) {

    public ExecutionQueue {
        now = now == null ? Optional.empty() : now;
        next = next == null ? Optional.empty() : next;
        if (later == null) {
            throw new NullPointerException("later");
        }
        validateUnique(now, next, later);
        if (next.filter(ExecutionRecommendationItem::notUrgent).isPresent()
                || later.orderedItems().stream()
                        .anyMatch(ExecutionRecommendationItem::notUrgent)) {
            throw new IllegalArgumentException("notUrgent is only valid for Now");
        }
    }

    private static void validateUnique(
            Optional<ExecutionRecommendationItem> now,
            Optional<ExecutionRecommendationItem> next,
            ExecutionLaterQueue later) {
        var identities = new HashSet<String>();
        now.ifPresent(item -> addIdentity(identities, item));
        next.ifPresent(item -> addIdentity(identities, item));
        later.orderedItems().forEach(item -> addIdentity(identities, item));
    }

    private static void addIdentity(
            HashSet<String> identities, ExecutionRecommendationItem item) {
        if (!identities.add(item.stableId())) {
            throw new IllegalArgumentException(
                    "stable identity must appear in only one output section");
        }
    }
}
