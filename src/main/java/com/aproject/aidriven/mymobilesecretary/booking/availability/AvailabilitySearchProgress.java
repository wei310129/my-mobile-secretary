package com.aproject.aidriven.mymobilesecretary.booking.availability;

import java.util.Objects;
import java.util.Optional;

public record AvailabilitySearchProgress(
        long sequence,
        Stage stage,
        Optional<String> sourceKey,
        Optional<AvailabilitySourceStatus> sourceStatus) {

    public AvailabilitySearchProgress {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(stage, "stage");
        sourceKey = Objects.requireNonNull(sourceKey, "sourceKey");
        sourceStatus = Objects.requireNonNull(sourceStatus, "sourceStatus");
        if (stage == Stage.SOURCE_COMPLETED
                && (sourceKey.isEmpty() || sourceStatus.isEmpty())) {
            throw new IllegalArgumentException("source completion requires source details");
        }
        if (stage != Stage.SOURCE_COMPLETED
                && (sourceKey.isPresent() || sourceStatus.isPresent())) {
            throw new IllegalArgumentException("search-level progress cannot have source details");
        }
    }

    public static AvailabilitySearchProgress started() {
        return new AvailabilitySearchProgress(
                0, Stage.STARTED, Optional.empty(), Optional.empty());
    }

    public static AvailabilitySearchProgress sourceCompleted(
            long sequence, AvailabilitySourceOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return new AvailabilitySearchProgress(
                sequence,
                Stage.SOURCE_COMPLETED,
                Optional.of(outcome.sourceKey()),
                Optional.of(outcome.status()));
    }

    public static AvailabilitySearchProgress terminal(long sequence) {
        return new AvailabilitySearchProgress(
                sequence, Stage.TERMINAL, Optional.empty(), Optional.empty());
    }

    public enum Stage {
        STARTED,
        SOURCE_COMPLETED,
        TERMINAL
    }
}
