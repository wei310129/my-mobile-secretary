package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import java.time.Instant;
import java.util.UUID;

public record CalendarPlanLifecycleView(
        UUID planId,
        CalendarPlanStatus status,
        long revision,
        Instant canceledAt,
        Instant archivedAt) {
}
