package com.aproject.aidriven.mymobilesecretary.booking.availability;

import java.util.List;
import java.util.Objects;

public record AvailabilitySearchResult(
        AvailabilitySearchStatus status,
        List<AvailabilityCandidate> eligibleCandidates,
        List<RecommendedCandidate> recommendations,
        List<AvailabilitySourceOutcome> sourceOutcomes) {

    public AvailabilitySearchResult {
        Objects.requireNonNull(status, "status");
        eligibleCandidates =
                List.copyOf(Objects.requireNonNull(eligibleCandidates, "eligibleCandidates"));
        recommendations =
                List.copyOf(Objects.requireNonNull(recommendations, "recommendations"));
        sourceOutcomes = List.copyOf(Objects.requireNonNull(sourceOutcomes, "sourceOutcomes"));
    }
}
