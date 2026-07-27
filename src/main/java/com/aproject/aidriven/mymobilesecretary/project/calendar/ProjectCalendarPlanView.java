package com.aproject.aidriven.mymobilesecretary.project.calendar;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import java.time.Instant;
import java.util.UUID;

public record ProjectCalendarPlanView(
        UUID bindingId,
        UUID projectId,
        UUID planId,
        ProjectCalendarBindingStatus bindingStatus,
        ProjectCalendarUnlinkReason unlinkReason,
        long bindingRevision,
        String title,
        String category,
        String onlineHost,
        CalendarPlanStatus planStatus,
        long planRevision,
        Instant unlinkedAt) {
}
