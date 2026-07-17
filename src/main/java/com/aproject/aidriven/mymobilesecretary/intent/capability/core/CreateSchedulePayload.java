package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Arguments for a time-blocked schedule with an explicit start and end. */
public record CreateSchedulePayload(
        @NotBlank @Size(max = 200) String title,
        @NotNull OffsetDateTime startAt,
        @NotNull OffsetDateTime endAt,
        @Size(max = 200) String placeName,
        ScheduleItem.Recurrence recurrence,
        LocalDate recurrenceUntil) {
}
