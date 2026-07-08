package com.aproject.aidriven.mymobilesecretary.api.place;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import java.time.Instant;

/** 地點的 API 回應格式。 */
public record PlaceResponse(
        Long id,
        String name,
        String address,
        double latitude,
        double longitude,
        String type,
        Instant createdAt
) {

    /** 由 domain 轉成回應 DTO。 */
    public static PlaceResponse from(Place place) {
        return new PlaceResponse(
                place.getId(),
                place.getName(),
                place.getAddress(),
                place.getLatitude(),
                place.getLongitude(),
                place.getType(),
                place.getCreatedAt());
    }
}
