package com.aproject.aidriven.mymobilesecretary.booking.availability;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record AvailabilityCandidate(
        UUID candidateId,
        String sourceKey,
        String inventoryIdentity,
        Instant expiresAt,
        boolean available,
        AvailabilityFeasibility feasibility,
        BigDecimal displayedPrice,
        Currency currency,
        boolean totalFullyKnown,
        int requiredAddOnCount,
        AvailabilityRisk refundRisk,
        AvailabilityRisk changeRisk,
        int restrictionCount) {

    public AvailabilityCandidate {
        Objects.requireNonNull(candidateId, "candidateId");
        sourceKey = requireText(sourceKey, "sourceKey");
        inventoryIdentity = requireText(inventoryIdentity, "inventoryIdentity");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(feasibility, "feasibility");
        Objects.requireNonNull(displayedPrice, "displayedPrice");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(refundRisk, "refundRisk");
        Objects.requireNonNull(changeRisk, "changeRisk");
        if (displayedPrice.signum() < 0) {
            throw new IllegalArgumentException("displayedPrice must not be negative");
        }
        if (requiredAddOnCount < 0) {
            throw new IllegalArgumentException("requiredAddOnCount must not be negative");
        }
        if (restrictionCount < 0) {
            throw new IllegalArgumentException("restrictionCount must not be negative");
        }
    }

    public boolean isEligible(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return available
                && clock.instant().isBefore(expiresAt)
                && feasibility != AvailabilityFeasibility.IMPOSSIBLE;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
