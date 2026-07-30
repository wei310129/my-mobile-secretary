package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.util.List;

public record CalendarIcsImportDocument(
        String method,
        List<CalendarIcsImportCandidate> candidates,
        List<String> warnings) {

    public CalendarIcsImportDocument {
        candidates = List.copyOf(candidates);
        warnings = List.copyOf(warnings);
    }
}
