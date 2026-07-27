package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarOrganizerAssignmentChange(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        UUID managerUserId,
        boolean rosterPermission,
        boolean notificationRecipient,
        CalendarOrganizerAssignmentAction action,
        long expectedRevision) {}
