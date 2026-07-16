package com.aproject.aidriven.mymobilesecretary.api.schedule;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Instant;
import java.time.LocalDate;

/** 行程的 API 回應格式。 */
public record ScheduleResponse(
        Long id,
        String title,
        Instant startAt,
        Instant endAt,
        Long placeId,
        ScheduleStatus status,
        ScheduleItem.Recurrence recurrence,
        LocalDate recurrenceUntil,
        Instant createdAt,
        Instant updatedAt
) {

    /** 由 domain 轉成回應 DTO。 */
    public static ScheduleResponse from(ScheduleItem item) {
        return new ScheduleResponse(
                item.getId(),
                item.getTitle(),
                item.getStartAt(),
                item.getEndAt(),
                item.getPlaceId(),
                item.getStatus(),
                item.getRecurrence(),
                item.getRecurrenceUntil(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}
