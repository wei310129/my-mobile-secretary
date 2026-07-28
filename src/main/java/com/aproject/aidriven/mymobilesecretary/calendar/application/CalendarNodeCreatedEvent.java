package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.time.Instant;
import java.util.UUID;

public record CalendarNodeCreatedEvent(
        UUID nodeId,
        String nodeLabel,
        Instant effectiveTime,
        Instant occurredAt) {}
