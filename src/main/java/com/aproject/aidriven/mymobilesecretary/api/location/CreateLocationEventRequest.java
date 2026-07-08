package com.aproject.aidriven.mymobilesecretary.api.location;

import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEventType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 回報位置事件的請求。
 *
 * @param occurredAt 事件在裝置上發生的時間;未提供時以伺服器收到時間代替
 * @param source     事件來源(選填),例如 api-simulated / ios
 */
public record CreateLocationEventRequest(
        @NotNull LocationEventType eventType,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        Instant occurredAt,
        @Size(max = 50) String source
) {
}
