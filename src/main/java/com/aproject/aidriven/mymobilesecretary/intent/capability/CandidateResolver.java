package com.aproject.aidriven.mymobilesecretary.intent.capability;

import java.util.List;

/** Candidate retrieval SPI; future embedding resolvers can implement the same contract. */
public interface CandidateResolver {

    int MAX_CANDIDATES = 12;

    /** Returns up to {@code limit} candidates, ordered from strongest to weakest. */
    List<CapabilityCandidate> resolve(String message, int limit);
}
