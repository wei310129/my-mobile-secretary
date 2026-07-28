package com.aproject.aidriven.mymobilesecretary.calendar.task;

import java.time.Instant;

public record CalendarTaskBoundEvent(
        Long taskId, CalendarTaskTarget.TargetKind targetKind, Instant occurredAt) {}
