package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.List;
import java.util.Objects;

/** Ranked candidate with non-sensitive local matching evidence. */
public record CapabilityCandidate(
        CapabilityDescriptor descriptor,
        long score,
        List<String> matchedTerms) {

    public CapabilityCandidate {
        Objects.requireNonNull(descriptor, "descriptor");
        if (score <= 0) {
            throw new IllegalArgumentException("candidate score must be positive");
        }
        Objects.requireNonNull(matchedTerms, "matchedTerms");
        matchedTerms = List.copyOf(matchedTerms);
    }
}
