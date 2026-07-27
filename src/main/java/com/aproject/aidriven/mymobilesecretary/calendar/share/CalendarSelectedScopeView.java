package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CalendarSelectedScopeView(
        UUID planId,
        String planTitle,
        String planStatus,
        String ownerDisplayName,
        List<UUID> grantSources,
        List<ActivityContext> activities,
        List<NodeTarget> nodes,
        List<DependencyMinimum> dependencies) {

    public record ActivityContext(
            UUID id, String title, boolean explicitlySelected, String safeHost) {}

    public record NodeTarget(
            UUID id,
            UUID activityId,
            String label,
            Instant resolvedTime,
            String expressionKind,
            Long offsetSeconds,
            String locationLabel,
            String criticality,
            String adjustability,
            String cancellationStatus,
            Instant canceledAt,
            long revision,
            String safeHost) {}

    public record DependencyMinimum(
            UUID nodeId,
            UUID dependentNodeId,
            Instant resolvedTime,
            long revision) {}
}
