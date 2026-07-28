package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CalendarShareScope(
        CalendarShareScopeMode mode,
        List<UUID> targetIds) {

    public CalendarShareScope {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(targetIds, "targetIds");
        targetIds = targetIds.stream()
                .map(id -> Objects.requireNonNull(id, "target id"))
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        if (mode == CalendarShareScopeMode.LIVE_WHOLE_PLAN
                && !targetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Whole-plan scope cannot contain explicit targets");
        }
        if (mode != CalendarShareScopeMode.LIVE_WHOLE_PLAN
                && targetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected scope requires at least one target");
        }
    }

    public static CalendarShareScope liveWholePlan() {
        return new CalendarShareScope(
                CalendarShareScopeMode.LIVE_WHOLE_PLAN, List.of());
    }

    public static CalendarShareScope selectedActivities(List<UUID> activityIds) {
        return new CalendarShareScope(
                CalendarShareScopeMode.SELECTED_ACTIVITIES, activityIds);
    }

    public static CalendarShareScope selectedNodes(List<UUID> nodeIds) {
        return new CalendarShareScope(
                CalendarShareScopeMode.SELECTED_NODES, nodeIds);
    }

    public String semanticKey() {
        if (mode == CalendarShareScopeMode.LIVE_WHOLE_PLAN) {
            return mode.name();
        }
        return mode.name()
                + "|"
                + targetIds.stream()
                        .map(UUID::toString)
                        .reduce((left, right) -> left + "," + right)
                        .orElse("");
    }
}
