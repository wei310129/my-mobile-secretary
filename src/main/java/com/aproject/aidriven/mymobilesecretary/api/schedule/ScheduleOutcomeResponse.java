package com.aproject.aidriven.mymobilesecretary.api.schedule;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.OutcomeReason;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcome;
import java.time.Instant;

/** 行程結果的 API 回應。 */
public record ScheduleOutcomeResponse(
        Long id,
        Long scheduleItemId,
        boolean onTime,
        Integer overrunMinutes,
        OutcomeReason reason,
        String note,
        Instant recordedAt
) {

    public static ScheduleOutcomeResponse from(ScheduleOutcome outcome) {
        return new ScheduleOutcomeResponse(
                outcome.getId(), outcome.getScheduleItemId(), outcome.isOnTime(),
                outcome.getOverrunMinutes(), outcome.getReason(), outcome.getNote(),
                outcome.getRecordedAt());
    }
}
