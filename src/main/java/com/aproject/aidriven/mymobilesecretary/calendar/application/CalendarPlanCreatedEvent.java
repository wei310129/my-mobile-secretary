package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.time.Instant;
import java.util.UUID;

public record CalendarPlanCreatedEvent(
        UUID planId, String title, Instant occurredAt) {}
