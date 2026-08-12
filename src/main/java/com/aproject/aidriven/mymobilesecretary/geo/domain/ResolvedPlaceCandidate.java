package com.aproject.aidriven.mymobilesecretary.geo.domain;

/** A validated place candidate used by durable workflows without retaining raw inbound text. */
public record ResolvedPlaceCandidate(
        String name, String address, double latitude, double longitude, String type) {

    public ResolvedPlaceCandidate {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("resolved place name is required");
        }
    }
}
