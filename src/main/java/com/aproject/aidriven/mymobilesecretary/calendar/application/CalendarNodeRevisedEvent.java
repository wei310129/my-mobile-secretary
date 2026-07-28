package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.time.Instant;
import java.util.UUID;

public record CalendarNodeRevisedEvent(
        UUID nodeId,
        String nodeLabel,
        long previousRevision,
        long currentRevision,
        Instant currentEffectiveTime,
        Instant occurredAt) {}
