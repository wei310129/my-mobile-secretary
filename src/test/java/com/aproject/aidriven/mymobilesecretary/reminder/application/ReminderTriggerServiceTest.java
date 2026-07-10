package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 提醒觸發的兩道守門測試:任務狀態、debounce 視窗。
 */
@ExtendWith(MockitoExtension.class)
class ReminderTriggerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(10);

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ReminderRepository reminderRepository;

    @Mock
    private ReminderDeliveryService deliveryService;

    private ReminderTriggerService service;

    @BeforeEach
    void setUp() {
        service = new ReminderTriggerService(
                taskRepository, reminderRepository, deliveryService,
                new ReminderProperties(WINDOW),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unknownTaskThrowsNotFound() {
        when(taskRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.tryTrigger(99L, "reason"))
                .isInstanceOf(NotFoundException.class);
    }

    /** 守門 1:已確認的任務不再提醒,連 debounce 查詢都不用做。 */
    @Test
    void confirmedTaskIsNotReminded() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        task.confirm(NOW);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));

        Optional<Reminder> result = service.tryTrigger(1L, "ENTER geofence: 全聯");

        assertThat(result).isEmpty();
        verify(reminderRepository, never()).save(any());
    }

    /** 守門 2:debounce 視窗內已提醒過 → 跳過,任務狀態不變。 */
    @Test
    void reminderWithinDebounceWindowIsSkipped() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(reminderRepository.existsByTaskIdAndTriggeredAtAfter(1L, NOW.minus(WINDOW))).thenReturn(true);

        Optional<Reminder> result = service.tryTrigger(1L, "ENTER geofence: 全聯");

        assertThat(result).isEmpty();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.CREATED);
        verify(reminderRepository, never()).save(any());
    }

    /** 兩道守門都通過 → 建立提醒、任務轉 REMINDED。 */
    @Test
    void triggerCreatesReminderAndMovesTaskToReminded() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        when(reminderRepository.existsByTaskIdAndTriggeredAtAfter(1L, NOW.minus(WINDOW))).thenReturn(false);
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Reminder> result = service.tryTrigger(1L, "ENTER geofence: 全聯");

        assertThat(result).isPresent();
        assertThat(result.get().getTriggerReason()).isEqualTo("ENTER geofence: 全聯");
        assertThat(result.get().getTriggeredAt()).isEqualTo(NOW);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.REMINDED);
        // 觸發成功必須送出通知
        verify(deliveryService).deliver(result.get(), task);
    }
}
