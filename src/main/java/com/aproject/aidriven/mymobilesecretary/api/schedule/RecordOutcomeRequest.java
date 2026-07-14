package com.aproject.aidriven.mymobilesecretary.api.schedule;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.OutcomeReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 回報行程結果。準時/超時互斥的細部驗證在 domain(ScheduleOutcome),
 * 這裡只擋型別與明顯非法值。
 *
 * @param onTime         是否準時
 * @param overrunMinutes 超時分鐘(onTime=false 時必填且為正)
 * @param reason         超時原因(可空)
 * @param note           補充說明(可空)
 */
public record RecordOutcomeRequest(
        @NotNull Boolean onTime,
        @Positive Integer overrunMinutes,
        OutcomeReason reason,
        @Size(max = 500) String note
) {
}
