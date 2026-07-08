package com.aproject.aidriven.mymobilesecretary.api.location;

import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEventType;
import java.time.Instant;

/** 位置事件的 API 回應格式(查詢用)。 */
public record LocationEventResponse(
        Long id,
        LocationEventType eventType,
        double latitude,
        double longitude,
        Instant occurredAt,
        String source,
        Instant createdAt
) {

    /** 由 domain 轉成回應 DTO。 */
    public static LocationEventResponse from(LocationEvent event) {
        return new LocationEventResponse(
                event.getId(),
                event.getEventType(),
                event.getLatitude(),
                event.getLongitude(),
                event.getOccurredAt(),
                event.getSource(),
                event.getCreatedAt());
    }
}
