package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
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
 * 升級催促的放行條件與催促鏈測試。
 */
@ExtendWith(MockitoExtension.class)
class ReminderEscalationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-10T10:00:00Z");
    private static final Duration INTERVAL = Duration.ofMinutes(15);
    private static final int MAX = 3;

    @Mock
    private ReminderRepository reminderRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ReminderDeliveryService deliveryService;

    @Mock
    private ReminderScheduleService scheduleService;

    @Mock
    private ReminderPreferenceService preferenceService;

    private ReminderEscalationService service;

    @BeforeEach
    void setUp() {
        service = new ReminderEscalationService(
                reminderRepository, taskRepository, deliveryService, scheduleService,
                preferenceService,
                new ReminderProperties(Duration.ofMinutes(10), INTERVAL, MAX),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(preferenceService.deferUntil(any(Task.class), any(Instant.class)))
                .thenReturn(Optional.empty());
    }

    /** 建立「已提醒未確認」的標準場景。 */
    private Reminder remindedScenario(Task task) {
        Reminder reminder = Reminder.triggered(1L, "任務到期", NOW);
        when(reminderRepository.findById(10L)).thenReturn(Optional.of(reminder));
        when(taskRepository.findById(1L)).thenReturn(Optional.of(task));
        return reminder;
    }

    @Test
    void escalatesUnconfirmedReminderAndSchedulesNext() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        task.remind(NOW);
        Reminder reminder = remindedScenario(task);

        Optional<Reminder> result = service.escalate(10L, 1);

        assertThat(result).isPresent();
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.ESCALATED);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ESCALATED);
        // 催促文字要標明第幾次
        verify(deliveryService).deliver(any(Reminder.class), any(Task.class), contains("第 1 次催促"));
        // 未催滿 → 排下一輪
        verify(scheduleService).scheduleEscalation(10L, 2, NOW.plus(INTERVAL));
    }

    @Test
    void quietHoursKeepSameAttemptAndDeferEscalation() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        task.remind(NOW);
        Reminder reminder = remindedScenario(task);
        Instant allowedAt = NOW.plus(Duration.ofHours(8));
        when(preferenceService.deferUntil(task, NOW)).thenReturn(Optional.of(allowedAt));

        assertThat(service.escalate(10L, 2)).isEmpty();

        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.TRIGGERED);
        verify(scheduleService).scheduleEscalation(10L, 2, allowedAt);
        verify(deliveryService, never()).deliver(any(), any(), anyString());
    }

    /** 催滿上限 → 通知照送,但不再排下一輪。 */
    @Test
    void lastEscalationDoesNotScheduleNext() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        task.remind(NOW);
        remindedScenario(task);

        Optional<Reminder> result = service.escalate(10L, MAX);

        assertThat(result).isPresent();
        verify(deliveryService).deliver(any(Reminder.class), any(Task.class), anyString());
        verify(scheduleService, never()).scheduleEscalation(anyLong(), anyInt(), any());
    }

    /** 提醒已確認 → 閉環完成,不催、不排。 */
    @Test
    void confirmedReminderIsNotEscalated() {
        Reminder reminder = Reminder.triggered(1L, "任務到期", NOW);
        reminder.confirm(NOW);
        when(reminderRepository.findById(10L)).thenReturn(Optional.of(reminder));

        Optional<Reminder> result = service.escalate(10L, 1);

        assertThat(result).isEmpty();
        verify(deliveryService, never()).deliver(any(), any(), anyString());
        verify(scheduleService, never()).scheduleEscalation(anyLong(), anyInt(), any());
    }

    /** 任務已確認(使用者只確認任務沒確認提醒)→ 同樣不催。 */
    @Test
    void confirmedTaskIsNotEscalated() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        task.remind(NOW);
        task.confirm(NOW);
        remindedScenario(task);

        Optional<Reminder> result = service.escalate(10L, 1);

        assertThat(result).isEmpty();
        verify(deliveryService, never()).deliver(any(), any(), anyString());
    }

    /** 提醒不存在 → 靜默丟棄,不炸。 */
    @Test
    void missingReminderIsDroppedSilently() {
        when(reminderRepository.findById(10L)).thenReturn(Optional.empty());

        assertThat(service.escalate(10L, 1)).isEmpty();
    }
}
