package com.aproject.aidriven.mymobilesecretary.geo.catalog.application;

/** A selected system point; adoption does not create a custom Place row. */
public record SystemPlaceCatalogAdoption(
        SystemPlaceCatalogView point,
        boolean copiedToPlanSnapshot,
        boolean customPlaceCreated) {

    public SystemPlaceCatalogAdoption {
        if (point == null) {
            throw new IllegalArgumentException("point is required");
        }
        if (customPlaceCreated) {
            throw new IllegalArgumentException(
                    "system catalog adoption cannot create a custom place");
        }
    }
}
