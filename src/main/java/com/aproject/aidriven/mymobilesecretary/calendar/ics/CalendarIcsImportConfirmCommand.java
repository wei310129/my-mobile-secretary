package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.ZoneId;
import java.util.UUID;

public record CalendarIcsImportConfirmCommand(
        String requestId,
        UUID itemId,
        long expectedRevision,
        boolean includeTitle,
        boolean includeTiming,
        ZoneId resolvedFloatingZone) {}
