package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActivityMutationDisambiguationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");

    @Mock TaskService taskService;
    @Mock ScheduleService scheduleService;

    @Test
    void sameNamedTaskAndScheduleMustBeSelectedBeforeRescheduling() {
        Task task = Task.create("健身", null, TaskPriority.NORMAL,
                Instant.parse("2026-07-19T02:00:00Z"), NOW);
        ScheduleItem schedule = ScheduleItem.propose("健身",
                Instant.parse("2026-07-19T02:00:00Z"),
                Instant.parse("2026-07-19T03:00:00Z"), null, NOW);
        when(taskService.listOpenTasks()).thenReturn(List.of(task));
        when(scheduleService.listSchedules(null)).thenReturn(List.of(schedule));
        ActivityMutationDisambiguationService service =
                new ActivityMutationDisambiguationService(taskService, scheduleService);

        IntentResult result = service.answer("明天的健身延後半小時").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("同名的待辦與行程", "1. 待辦", "2. 行程",
                "尚未異動", "修改待辦 健身", "修改行程 健身");
        verifyNoMoreInteractions(taskService, scheduleService);
    }

    @Test
    void explicitKindDoesNotInterceptTheMutation() {
        ActivityMutationDisambiguationService service =
                new ActivityMutationDisambiguationService(taskService, scheduleService);

        assertThat(service.answer("把健身這個行程延後半小時")).isEmpty();
    }
}
