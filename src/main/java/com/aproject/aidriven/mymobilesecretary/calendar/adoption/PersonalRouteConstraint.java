package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PersonalRouteConstraint(
        UUID planId,
        UUID nodeId,
        UUID sourceCreatedByUserId,
        String nodeKey,
        Instant effectiveTime,
        CalendarLocation location,
        Adjustability adjustability,
        long nodeRevision) {

    public PersonalRouteConstraint {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(sourceCreatedByUserId, "sourceCreatedByUserId");
        if (nodeKey == null || nodeKey.isBlank()) {
            throw new IllegalArgumentException("nodeKey must not be blank");
        }
        Objects.requireNonNull(effectiveTime, "effectiveTime");
        Objects.requireNonNull(adjustability, "adjustability");
        if (nodeRevision < 1) {
            throw new IllegalArgumentException("nodeRevision must be positive");
        }
    }
}
