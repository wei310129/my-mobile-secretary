package com.aproject.aidriven.mymobilesecretary.api.place;

import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogView;

/** API representation of a system catalog point. */
public record SystemPlaceCatalogResponse(
        String catalogKey,
        String logicalKey,
        String logicalName,
        String pointName,
        String region,
        String address,
        Double latitude,
        Double longitude,
        boolean coordinatesPresent,
        String category,
        String sourceName,
        String sourceUrl) {

    public static SystemPlaceCatalogResponse from(SystemPlaceCatalogView view) {
        return new SystemPlaceCatalogResponse(
                view.catalogKey(), view.logicalKey(), view.logicalName(), view.pointName(),
                view.region(), view.address(), view.latitude(), view.longitude(),
                view.hasCoordinates(), view.category(), view.sourceName(), view.sourceUrl());
    }
}
