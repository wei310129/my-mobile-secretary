package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Task 行為方法測試:狀態轉換的守門與時間更新。
 */
class TaskTest {

    private static final Instant T0 = Instant.parse("2026-07-09T08:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-09T09:00:00Z");

    @Test
    void createStartsInCreatedStatusWithGivenTime() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, T0);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CREATED);
        assertThat(task.getCreatedAt()).isEqualTo(T0);
        assertThat(task.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void confirmMovesToConfirmedAndBumpsUpdatedAt() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, T0);

        task.confirm(T1);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CONFIRMED);
        assertThat(task.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void confirmTwiceIsRejected() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, T0);
        task.confirm(T1);

        assertThatThrownBy(() -> task.confirm(T1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE_TRANSITION");
    }

    @Test
    void cancelAfterConfirmIsRejected() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, T0);
        task.confirm(T1);

        assertThatThrownBy(() -> task.cancel(T1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE_TRANSITION");
    }

    /** 改期限:未結案任務可改(含清空),已結案不可。 */
    @Test
    void changeDueAtOnOpenTaskUpdatesDueAndTimestamp() {
        Task task = Task.create("拿包裹", null, TaskPriority.NORMAL, T0, T0);
        Instant newDue = Instant.parse("2026-07-09T11:00:00Z");

        task.changeDueAt(newDue, T1);

        assertThat(task.getDueAt()).isEqualTo(newDue);
        assertThat(task.getUpdatedAt()).isEqualTo(T1);

        task.changeDueAt(null, T1);
        assertThat(task.getDueAt()).isNull();
    }

    @Test
    void changeDueAtOnClosedTaskIsRejected() {
        Task confirmed = Task.create("拿包裹", null, TaskPriority.NORMAL, T0, T0);
        confirmed.confirm(T1);
        Task canceled = Task.create("買醬油", null, TaskPriority.NORMAL, T0, T0);
        canceled.cancel(T1);

        assertThatThrownBy(() -> confirmed.changeDueAt(T1, T1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE_TRANSITION");
        assertThatThrownBy(() -> canceled.changeDueAt(T1, T1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE_TRANSITION");
    }

    /** 提醒後可升級,且可重複升級(第 2、3 次催促)。 */
    @Test
    void escalateAfterRemindAndRepeatedly() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, T0);
        task.remind(T1);

        task.escalate(T1);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ESCALATED);
        assertThat(task.canBeEscalated()).isTrue();

        task.escalate(T1);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ESCALATED);
    }

    /** 還沒提醒過的任務不可直接升級。 */
    @Test
    void escalateBeforeRemindIsRejected() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, T0);

        assertThat(task.canBeEscalated()).isFalse();
        assertThatThrownBy(() -> task.escalate(T1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE_TRANSITION");
    }
}
