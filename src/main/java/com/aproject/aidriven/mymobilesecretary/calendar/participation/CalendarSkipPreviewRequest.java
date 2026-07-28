package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarSkipPreviewRequest(
        UUID planId, CalendarParticipationScope scope) {}
