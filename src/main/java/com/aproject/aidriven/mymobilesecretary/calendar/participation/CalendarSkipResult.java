package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.List;
import java.util.UUID;

public record CalendarSkipResult(
        CalendarParticipationStatus participationStatus,
        boolean adoptionRemoved,
        boolean routineMessagesSuppressed,
        List<UUID> canceledReminderIds,
        boolean replayed) {}
