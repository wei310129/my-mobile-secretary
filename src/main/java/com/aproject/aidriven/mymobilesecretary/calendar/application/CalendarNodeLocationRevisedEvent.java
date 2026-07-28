package com.aproject.aidriven.mymobilesecretary.calendar.application;

import java.time.Instant;
import java.util.UUID;

public record CalendarNodeLocationRevisedEvent(
        UUID nodeId, String nodeLabel, long revision, Instant occurredAt) {}
