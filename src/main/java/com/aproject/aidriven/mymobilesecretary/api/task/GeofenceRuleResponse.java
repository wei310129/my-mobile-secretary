package com.aproject.aidriven.mymobilesecretary.api.task;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import java.time.Instant;

/** geofence 規則的 API 回應格式。 */
public record GeofenceRuleResponse(
        Long id,
        Long taskId,
        Long placeId,
        int radiusMeters,
        TriggerType triggerType,
        boolean enabled,
        Instant createdAt
) {

    /** 由 domain 轉成回應 DTO。 */
    public static GeofenceRuleResponse from(GeofenceRule rule) {
        return new GeofenceRuleResponse(
                rule.getId(),
                rule.getTaskId(),
                rule.getPlaceId(),
                rule.getRadiusMeters(),
                rule.getTriggerType(),
                rule.isEnabled(),
                rule.getCreatedAt());
    }
}
