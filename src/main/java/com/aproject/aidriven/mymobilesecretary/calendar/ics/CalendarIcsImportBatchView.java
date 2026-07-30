package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CalendarIcsImportBatchView(
        UUID id,
        long revision,
        String state,
        String schedulingMethod,
        int eventCount,
        Instant expiresAt,
        List<CalendarIcsImportItemView> items) {

    public CalendarIcsImportBatchView {
        items = List.copyOf(items);
    }
}
