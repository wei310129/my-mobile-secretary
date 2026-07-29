package com.aproject.aidriven.mymobilesecretary.booking.availability;

import java.util.Objects;
import java.util.Set;

public record RecommendedCandidate(
        AvailabilityCandidate candidate, Set<RecommendationBadge> badges) {

    public RecommendedCandidate {
        Objects.requireNonNull(candidate, "candidate");
        badges = Set.copyOf(Objects.requireNonNull(badges, "badges"));
        if (badges.isEmpty()) {
            throw new IllegalArgumentException("badges must not be empty");
        }
    }
}
