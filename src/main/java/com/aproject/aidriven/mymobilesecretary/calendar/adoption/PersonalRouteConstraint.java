package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import java.time.Instant;

public record PersonalRouteConstraint(
        String nodeKey,
        Instant effectiveTime,
        CalendarLocation location,
        Adjustability adjustability,
        long nodeRevision) {}
