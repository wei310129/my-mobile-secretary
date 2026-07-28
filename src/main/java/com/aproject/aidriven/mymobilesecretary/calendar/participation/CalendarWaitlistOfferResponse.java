package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarWaitlistOfferResponse(
        String requestId,
        UUID offerId,
        long expectedRevision) {}
