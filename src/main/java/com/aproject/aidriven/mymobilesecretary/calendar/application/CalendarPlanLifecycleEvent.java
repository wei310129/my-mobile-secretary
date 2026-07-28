package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.time.Instant;
import java.util.UUID;

public record CalendarPlanLifecycleEvent(
        UUID planId,
        String title,
        Action action,
        Instant occurredAt) {

    public enum Action {
        CANCELED,
        ARCHIVED
    }
}
