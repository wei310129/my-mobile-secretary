package com.aproject.aidriven.mymobilesecretary.api.place;

import com.aproject.aidriven.mymobilesecretary.geo.catalog.application.SystemPlaceCatalogAdoption;

/** API response for selecting a system point without creating a custom place. */
public record SystemPlaceCatalogAdoptionResponse(
        SystemPlaceCatalogResponse point,
        boolean copiedToPlanSnapshot,
        boolean customPlaceCreated) {

    public static SystemPlaceCatalogAdoptionResponse from(SystemPlaceCatalogAdoption adoption) {
        return new SystemPlaceCatalogAdoptionResponse(
                SystemPlaceCatalogResponse.from(adoption.point()),
                adoption.copiedToPlanSnapshot(), adoption.customPlaceCreated());
    }
}
