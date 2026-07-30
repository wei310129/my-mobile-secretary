package com.aproject.aidriven.mymobilesecretary.booking.availability;

import java.util.Objects;

public record AvailabilitySourceOutcome(
        String sourceKey, AvailabilitySourceStatus status, int receivedCandidateCount) {

    public AvailabilitySourceOutcome {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey must not be blank");
        }
        Objects.requireNonNull(status, "status");
        if (receivedCandidateCount < 0) {
            throw new IllegalArgumentException("receivedCandidateCount must not be negative");
        }
        if (status != AvailabilitySourceStatus.SUCCESS && receivedCandidateCount != 0) {
            throw new IllegalArgumentException("unsuccessful source cannot report candidates");
        }
    }
}
