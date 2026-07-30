package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CalendarIcsRecurrenceProjection(
        List<CalendarIcsEvent> events, Set<UUID> recurrenceOwnerIds) {}
