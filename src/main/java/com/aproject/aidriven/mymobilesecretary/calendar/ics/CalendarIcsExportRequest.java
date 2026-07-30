package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.LocalDate;
import java.util.UUID;

public record CalendarIcsExportRequest(
        String requestId,
        UUID planId,
        LocalDate windowStart,
        LocalDate windowEndExclusive,
        CalendarIcsExportProfile profile,
        String lossReport,
        byte[] content) {}
