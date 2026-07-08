package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 狀態機轉換規則測試:合法路徑放行、非法路徑拒絕、終止狀態不可再轉。
 */
class TaskStatusTest {

    @ParameterizedTest
    @CsvSource({
            // 正常閉環:建立 → 排程 → 提醒 → 確認
            "CREATED, SCHEDULED",
            "SCHEDULED, REMINDED",
            "REMINDED, CONFIRMED",
            // 未回應升級,且可重複升級
            "REMINDED, ESCALATED",
            "ESCALATED, ESCALATED",
            "ESCALATED, CONFIRMED",
            // 提醒發生前就自行完成
            "CREATED, CONFIRMED",
            "SCHEDULED, CONFIRMED",
            // 任何非終止狀態都可取消
            "CREATED, CANCELED",
            "SCHEDULED, CANCELED",
            "REMINDED, CANCELED",
            "ESCALATED, CANCELED",
    })
    void allowsLegalTransitions(TaskStatus from, TaskStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // 不可回頭
            "SCHEDULED, CREATED",
            "REMINDED, SCHEDULED",
            "ESCALATED, REMINDED",
            // 不可跳過提醒直接升級
            "CREATED, ESCALATED",
            "SCHEDULED, ESCALATED",
    })
    void rejectsIllegalTransitions(TaskStatus from, TaskStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    /** 終止狀態(CONFIRMED/CANCELED)之後,任何轉換都不允許。 */
    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void terminalStatesAllowNothing(TaskStatus target) {
        assertThat(TaskStatus.CONFIRMED.canTransitionTo(target)).isFalse();
        assertThat(TaskStatus.CANCELED.canTransitionTo(target)).isFalse();
    }
}
