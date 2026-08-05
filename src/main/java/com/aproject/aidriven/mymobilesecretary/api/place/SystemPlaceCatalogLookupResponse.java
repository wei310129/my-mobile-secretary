package com.aproject.aidriven.mymobilesecretary.api.place;

import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogLookup;
import java.util.List;

/** API response for deterministic system catalog lookup. */
public record SystemPlaceCatalogLookupResponse(
        SystemPlaceCatalogLookup.Status status,
        String query,
        String region,
        List<SystemPlaceCatalogResponse> candidates) {

    public static SystemPlaceCatalogLookupResponse from(SystemPlaceCatalogLookup lookup) {
        return new SystemPlaceCatalogLookupResponse(
                lookup.status(), lookup.query(), lookup.region(),
                lookup.candidates().stream().map(SystemPlaceCatalogResponse::from).toList());
    }
}
