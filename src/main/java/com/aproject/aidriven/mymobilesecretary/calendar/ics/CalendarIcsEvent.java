package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CalendarIcsEvent(
        UUID sourceId,
        String sourceType,
        String summary,
        String description,
        Instant startsAt,
        Instant endsAt,
        ZoneId zoneId,
        LocalDate allDayStart,
        LocalDate allDayEndExclusive) {

    public CalendarIcsEvent(
            UUID sourceId,
            String summary,
            String description,
            Instant startsAt,
            Instant endsAt,
            ZoneId zoneId) {
        this(sourceId, "plan", summary, description, startsAt, endsAt, zoneId, null, null);
    }

    public static CalendarIcsEvent activity(
            UUID id,
            String summary,
            String description,
            Instant startsAt,
            Instant endsAt,
            ZoneId zoneId) {
        return new CalendarIcsEvent(
                id, "activity", summary, description, startsAt, endsAt, zoneId, null, null);
    }

    public static CalendarIcsEvent allDay(
            UUID id,
            String sourceType,
            String summary,
            String description,
            LocalDate start,
            LocalDate endExclusive) {
        return new CalendarIcsEvent(
                id, sourceType, summary, description, null, null, null, start, endExclusive);
    }

    public CalendarIcsEvent {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!List.of("plan", "activity", "route-node").contains(sourceType)) {
            throw new IllegalArgumentException("Unsupported ICS event source type");
        }
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(description, "description");
        boolean timed = startsAt != null || endsAt != null || zoneId != null;
        boolean allDay = allDayStart != null || allDayEndExclusive != null;
        if (timed == allDay) {
            throw new IllegalArgumentException(
                    "ICS event must be exactly one of timed or all-day");
        }
        if (timed
                && (startsAt == null
                        || endsAt == null
                        || zoneId == null
                        || !endsAt.isAfter(startsAt))) {
            throw new IllegalArgumentException("Invalid timed ICS event");
        }
        if (allDay
                && (allDayStart == null
                        || allDayEndExclusive == null
                        || !allDayEndExclusive.isAfter(allDayStart))) {
            throw new IllegalArgumentException("Invalid all-day ICS event");
        }
    }

    public boolean timed() {
        return startsAt != null;
    }
}
