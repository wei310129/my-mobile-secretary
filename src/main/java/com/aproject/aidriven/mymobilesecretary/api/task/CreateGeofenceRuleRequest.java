package com.aproject.aidriven.mymobilesecretary.api.task;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 建立 geofence 規則的請求(taskId 來自路徑)。
 * 半徑上下限與 domain 的 GeofenceRule 常數一致,雙層防護。
 */
public record CreateGeofenceRuleRequest(
        @NotNull Long placeId,
        @NotNull
        @Min(GeofenceRule.MIN_RADIUS_METERS)
        @Max(GeofenceRule.MAX_RADIUS_METERS)
        Integer radiusMeters,
        @NotNull TriggerType triggerType
) {
}
