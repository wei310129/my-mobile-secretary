package com.aproject.aidriven.mymobilesecretary.geo.catalog.application;

import java.util.List;

/** Deterministic result of a system catalog lookup. */
public record SystemPlaceCatalogLookup(
        Status status,
        String query,
        String region,
        List<SystemPlaceCatalogView> candidates) {

    public SystemPlaceCatalogLookup {
        candidates = List.copyOf(candidates);
    }

    public enum Status {
        EXACT,
        MULTIPOINT,
        CROSS_REGION,
        MULTIPLE,
        NOT_FOUND
    }
}
