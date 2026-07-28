package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarOrganizerAssignmentView(
        UUID planId,
        CalendarParticipationScope scope,
        UUID managerUserId,
        boolean rosterPermission,
        boolean notificationRecipient,
        CalendarOrganizerAssignmentStatus status,
        long revision) {}
