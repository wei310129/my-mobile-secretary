package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarRegistrationWithdrawalCommand(
        String requestId,
        UUID planId,
        CalendarParticipationScope scope,
        long expectedRevision,
        String reason,
        boolean shareReason) {}
