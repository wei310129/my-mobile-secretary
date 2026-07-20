package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.FlexibleDayTaskPlan;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.FlexibleDayTaskPlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlexibleDayTaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void choosesKnownFreeSlotWithoutCreatingCalendarSchedule() {
        FlexibleDayTaskPlanRepository repository = mock(FlexibleDayTaskPlanRepository.class);
        TaskService tasks = mock(TaskService.class);
        FreeSlotService slots = mock(FreeSlotService.class);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(17L);
        when(tasks.createTask(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task);
        Instant selected = Instant.parse("2026-07-20T01:30:00Z");
        when(slots.suggest(any(), any(), any(), any()))
                .thenReturn(List.of(new FreeSlotService.Slot(
                        selected, selected.plus(Duration.ofMinutes(15)))));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        FlexibleDayTaskService service = new FlexibleDayTaskService(
                repository, tasks, slots, Clock.fixed(NOW, ZoneOffset.UTC));

        var result = service.plan("整理帳務", "有空時做", LocalDate.of(2026, 7, 20),
                LocalDate.of(2026, 7, 20), null,
                FlexibleDayTaskPlan.SourceKind.USER_REQUEST);

        assertThat(result.scheduleSuggested()).isTrue();
        assertThat(result.plan().getTargetDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(result.plan().getRemindAt()).isEqualTo(selected);
        verify(tasks).createTask("整理帳務", "有空時做", TaskPriority.NORMAL, selected,
                Task.Category.PERSONAL, Task.Recurrence.NONE, Task.ConditionType.NONE, null);
    }
}
