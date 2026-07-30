package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import java.time.Instant;
import java.util.UUID;

public record CalendarIcsExportArtifact(
        UUID id, String token, Instant expiresAt, String lossReport) {}
