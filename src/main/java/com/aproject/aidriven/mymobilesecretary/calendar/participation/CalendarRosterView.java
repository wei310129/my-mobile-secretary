package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.List;

public record CalendarRosterView(
        List<CalendarRosterEntry> entries,
        int committed,
        int waitlisted,
        int approvalRequired,
        int withdrawn,
        int removed,
        int remainingCapacity,
        int overCapacity) {

    public CalendarRosterView {
        entries = List.copyOf(entries);
    }
}
