package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record CalendarIcsImportItemView(
        UUID id,
        int ordinal,
        long revision,
        String title,
        String description,
        String location,
        String placementKind,
        LocalDateTime timedStart,
        LocalDateTime timedEnd,
        String zoneId,
        boolean floatingTime,
        LocalDate allDayStart,
        LocalDate allDayEndExclusive,
        String recurrenceSummary,
        boolean recurrenceSupported,
        List<Long> reminderOffsetSeconds,
        String warnings,
        String state,
        UUID materializedPlanId) {

    public CalendarIcsImportItemView {
        reminderOffsetSeconds = List.copyOf(reminderOffsetSeconds);
    }
}
