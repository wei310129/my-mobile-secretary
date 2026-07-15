package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TaskLifestyleServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-01T00:00:00Z");

    @Mock
    private TaskRepository repository;
    @Mock
    private ReminderScheduleService reminderSchedule;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(repository, reminderSchedule, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void updateTaskChangesOnlyProvidedFields() {
        Task task = recurringTask(NOW.plusSeconds(3600));
        when(repository.findById(7L)).thenReturn(Optional.of(task));

        Task changed = service.updateTask(7L, "準備季報", "帶合約",
                TaskPriority.HIGH, Task.Category.WORK);

        assertThat(changed.getTitle()).isEqualTo("準備季報");
        assertThat(changed.getDescription()).isEqualTo("帶合約");
        assertThat(changed.getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(changed.getCategory()).isEqualTo(Task.Category.WORK);
        assertThat(changed.getRecurrence()).isEqualTo(Task.Recurrence.WEEKLY);
    }

    @Test
    void pauseAndResumeKeepRuleButSynchronizeQueue() {
        Instant due = NOW.plusSeconds(3600);
        Task task = recurringTask(due);
        when(repository.findById(7L)).thenReturn(Optional.of(task));

        service.pauseRecurrence(7L);
        assertThat(task.isRecurrencePaused()).isTrue();
        verify(reminderSchedule).removeDueReminder(7L);

        service.resumeRecurrence(7L);
        assertThat(task.isRecurrencePaused()).isFalse();
        verify(reminderSchedule).scheduleDueReminder(7L, due);
    }

    @Test
    void skipOccurrenceMovesExactlyOneWeek() {
        Instant due = NOW.plusSeconds(3600);
        Task task = recurringTask(due);
        when(repository.findById(7L)).thenReturn(Optional.of(task));

        service.skipRecurringOccurrence(7L);

        assertThat(task.getDueAt()).isEqualTo(due.plusSeconds(7 * 24 * 3600));
        verify(reminderSchedule).scheduleDueReminder(7L, task.getDueAt());
    }

    private static Task recurringTask(Instant due) {
        Task task = Task.create("週報", null, TaskPriority.NORMAL, due,
                Task.Category.WORK, Task.Recurrence.WEEKLY,
                Task.ConditionType.NONE, null, NOW);
        ReflectionTestUtils.setField(task, "id", 7L);
        return task;
    }
}
