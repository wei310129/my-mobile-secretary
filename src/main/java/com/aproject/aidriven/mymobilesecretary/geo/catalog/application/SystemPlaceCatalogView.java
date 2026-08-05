package com.aproject.aidriven.mymobilesecretary.geo.catalog.application;

/** Public-safe typed evidence for one system catalog point. */
public record SystemPlaceCatalogView(
        String catalogKey,
        String logicalKey,
        String logicalName,
        String pointName,
        String region,
        String address,
        Double latitude,
        Double longitude,
        String category,
        String sourceName,
        String sourceUrl) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
