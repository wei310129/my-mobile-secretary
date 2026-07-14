package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 行程結果不變式測試:準時與超時互斥,超時必有正分鐘數——
 * 這些資料之後餵緩衝規則,矛盾資料會汙染統計。
 */
class ScheduleOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-07-14T02:00:00Z");

    @Test
    void onTimeOutcomeHasNoOverrunFields() {
        ScheduleOutcome outcome = ScheduleOutcome.onTime(1L, "順利", NOW);

        assertThat(outcome.isOnTime()).isTrue();
        assertThat(outcome.getOverrunMinutes()).isNull();
        assertThat(outcome.getReason()).isNull();
        assertThat(outcome.getNote()).isEqualTo("順利");
    }

    @Test
    void overrunOutcomeKeepsMinutesAndReason() {
        ScheduleOutcome outcome = ScheduleOutcome.overrun(1L, 30, OutcomeReason.MEETING_OVERRUN, "會開太久", NOW);

        assertThat(outcome.isOnTime()).isFalse();
        assertThat(outcome.getOverrunMinutes()).isEqualTo(30);
        assertThat(outcome.getReason()).isEqualTo(OutcomeReason.MEETING_OVERRUN);
    }

    /** 原因可不填(使用者常只說「晚了半小時」)。 */
    @Test
    void overrunReasonIsOptional() {
        ScheduleOutcome outcome = ScheduleOutcome.overrun(1L, 15, null, null, NOW);

        assertThat(outcome.getReason()).isNull();
    }

    @Test
    void overrunMinutesMustBePositive() {
        assertThatThrownBy(() -> ScheduleOutcome.overrun(1L, 0, null, null, NOW))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ScheduleOutcome.overrun(1L, -5, null, null, NOW))
                .isInstanceOf(BusinessException.class);
    }
}
