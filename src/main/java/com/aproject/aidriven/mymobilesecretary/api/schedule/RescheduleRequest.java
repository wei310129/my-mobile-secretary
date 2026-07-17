package com.aproject.aidriven.mymobilesecretary.api.schedule;

import java.time.Instant;
import jakarta.validation.constraints.NotNull;

/** 改時間的請求(會重新過可行性把關)。 */
public record RescheduleRequest(
        @NotNull Instant startAt,
        @NotNull Instant endAt
) {
}
