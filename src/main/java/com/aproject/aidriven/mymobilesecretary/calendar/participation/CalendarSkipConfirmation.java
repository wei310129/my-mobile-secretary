package com.aproject.aidriven.mymobilesecretary.calendar.participation;

public record CalendarSkipConfirmation(
        String requestId,
        String confirmationToken,
        String digest,
        long sourceRevision) {}
