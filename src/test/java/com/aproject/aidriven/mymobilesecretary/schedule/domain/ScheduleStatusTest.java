package com.aproject.aidriven.mymobilesecretary.schedule.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 行程狀態機轉換規則測試。
 */
class ScheduleStatusTest {

    @ParameterizedTest
    @CsvSource({
            // 可行放行 / 強制確認
            "PROPOSED, CONFIRMED",
            // 改時間重新驗算
            "PROPOSED, PROPOSED",
            "CONFIRMED, PROPOSED",
            // 暫無想法進 pending,之後再安排
            "PROPOSED, PENDING",
            "PENDING, PROPOSED",
            "PENDING, CANCELED",
            // 放棄提案
            "PROPOSED, REJECTED",
            // 已確認行程的收尾
            "CONFIRMED, CANCELED",
            "CONFIRMED, COMPLETED",
    })
    void allowsLegalTransitions(ScheduleStatus from, ScheduleStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // 沒確認過的不能直接完成/取消(PROPOSED 用 reject 收掉)
            "PROPOSED, COMPLETED",
            "PROPOSED, CANCELED",
            "PENDING, CONFIRMED",
            "PENDING, COMPLETED",
            // 已確認不能再被 reject 或 park
            "CONFIRMED, REJECTED",
            "CONFIRMED, PENDING",
    })
    void rejectsIllegalTransitions(ScheduleStatus from, ScheduleStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    /** 終止狀態不可再轉。 */
    @ParameterizedTest
    @EnumSource(ScheduleStatus.class)
    void terminalStatesAllowNothing(ScheduleStatus target) {
        assertThat(ScheduleStatus.REJECTED.canTransitionTo(target)).isFalse();
        assertThat(ScheduleStatus.CANCELED.canTransitionTo(target)).isFalse();
        assertThat(ScheduleStatus.COMPLETED.canTransitionTo(target)).isFalse();
    }
}
