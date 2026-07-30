package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record CalendarIcsImportCandidate(
        String uid,
        String recurrenceId,
        int sequence,
        String title,
        String description,
        String location,
        Instant startsAt,
        Instant endsAt,
        ZoneId zoneId,
        LocalDateTime floatingStartsAt,
        LocalDateTime floatingEndsAt,
        LocalDate allDayStart,
        LocalDate allDayEndExclusive,
        String recurrenceRule,
        boolean recurrenceRuleSupported,
        List<Duration> reminderSuggestions,
        List<String> warnings) {

    public CalendarIcsImportCandidate {
        reminderSuggestions = List.copyOf(reminderSuggestions);
        warnings = List.copyOf(warnings);
    }

    public boolean timed() {
        return startsAt != null || floatingStartsAt != null;
    }

    public boolean floating() {
        return floatingStartsAt != null;
    }

    public boolean recurring() {
        return recurrenceRule != null;
    }
}
