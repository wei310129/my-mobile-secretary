package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CalendarShareScopePreview(
        CalendarShareScope scope,
        int visibleTargetCount,
        String digest,
        List<DependencyMinimum> dependencyMinimums) {

    public record DependencyMinimum(
            UUID nodeId,
            UUID dependentNodeId,
            Instant resolvedTime,
            long revision) {}
}
