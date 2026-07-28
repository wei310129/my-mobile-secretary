package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import java.util.UUID;

public record CalendarWaitlistExpirySweepResult(
        UUID expiredOfferId,
        long expiredOfferRevision,
        CalendarWaitlistOfferView nextOffer) {}
