package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.List;
import java.util.UUID;

public record CalendarWaitlistReorderCommand(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        List<Entry> orderedEntries,
        String reason) {

    public record Entry(UUID entryId, long expectedRevision) {}
}
