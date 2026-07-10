package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Reminder 行為測試:升級與確認的狀態守門。
 */
class ReminderTest {

    private static final Instant NOW = Instant.parse("2026-07-10T08:00:00Z");

    @Test
    void escalateMovesToEscalatedAndCanRepeat() {
        Reminder reminder = Reminder.triggered(1L, "ENTER geofence: 全聯", NOW);

        reminder.escalate(NOW);
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.ESCALATED);

        // 第 2、3 次催促:重複升級合法
        reminder.escalate(NOW);
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.ESCALATED);
    }

    @Test
    void escalateAfterConfirmIsRejected() {
        Reminder reminder = Reminder.triggered(1L, "ENTER geofence: 全聯", NOW);
        reminder.confirm(NOW);

        assertThatThrownBy(() -> reminder.escalate(NOW))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE_TRANSITION");
    }

    /** 已升級的提醒仍可確認(催促中使用者回報)。 */
    @Test
    void confirmAfterEscalateIsAllowed() {
        Reminder reminder = Reminder.triggered(1L, "ENTER geofence: 全聯", NOW);
        reminder.escalate(NOW);

        reminder.confirm(NOW);

        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.CONFIRMED);
        assertThat(reminder.getConfirmedAt()).isEqualTo(NOW);
    }
}
