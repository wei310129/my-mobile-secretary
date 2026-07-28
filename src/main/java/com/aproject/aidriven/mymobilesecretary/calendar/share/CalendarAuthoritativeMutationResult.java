package com.aproject.aidriven.mymobilesecretary.calendar.share;

import java.util.UUID;

public record CalendarAuthoritativeMutationResult(
        UUID mutationId, UUID nodeId, Kind kind, long nodeRevision) {

    public enum Kind {
        NODE_TIME,
        NODE_LOCATION,
        NODE_CANCELLATION
    }
}
