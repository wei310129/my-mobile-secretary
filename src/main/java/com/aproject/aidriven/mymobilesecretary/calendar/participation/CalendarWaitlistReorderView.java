package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.List;
import java.util.UUID;

public record CalendarWaitlistReorderView(
        UUID planId,
        CalendarParticipationScope scope,
        List<Entry> entries,
        long resultRevision) {

    public record Entry(
            UUID entryId, long queueSequence, long revision) {}
}
