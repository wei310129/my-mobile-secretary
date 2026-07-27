package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarOwnershipTransferAcceptance(
        String requestId,
        UUID transferId,
        long expectedTransferRevision,
        long expectedOwnershipRevision) {}
