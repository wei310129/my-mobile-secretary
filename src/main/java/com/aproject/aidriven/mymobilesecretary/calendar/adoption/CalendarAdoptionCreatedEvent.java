package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import java.time.Instant;
import java.util.UUID;

public record CalendarAdoptionCreatedEvent(
        UUID adoptionId,
        String planTitle,
        int selectedNodeCount,
        Instant occurredAt) {}
