package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record CalendarIcsEvent(
        UUID planId,
        String summary,
        String description,
        Instant startsAt,
        Instant endsAt,
        ZoneId zoneId) {

    public CalendarIcsEvent {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(startsAt, "startsAt");
        Objects.requireNonNull(endsAt, "endsAt");
        Objects.requireNonNull(zoneId, "zoneId");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("ICS event end must be after start");
        }
    }
}
