package com.aproject.aidriven.mymobilesecretary.api.schedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 提出行程的請求。
 *
 * @param placeId 行程地點(選填;無地點就不做交通可行性檢查)
 */
public record CreateScheduleRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        Long placeId
) {
}
