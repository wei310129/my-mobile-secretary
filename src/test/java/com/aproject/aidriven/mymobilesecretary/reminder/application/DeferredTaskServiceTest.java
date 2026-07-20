package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.DeferredTask;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.DeferredTaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeferredTaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T13:00:00Z");

    private DeferredTaskRepository repository;
    private TaskService taskService;
    private DeferredTaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeferredTaskRepository.class);
        taskService = mock(TaskService.class);
        service = new DeferredTaskService(
                repository, taskService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.save(any(DeferredTask.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void uniqueOpenPredecessorCreatesWaitingDefinitionOnly() {
        Task predecessor = task(7L, "拿公司電腦");
        when(taskService.findOpenTasksMatching("拿公司電腦"))
                .thenReturn(List.of(predecessor));
        when(repository.findFirstByPredecessorTaskIdAndTitleIgnoreCaseAndStatus(
                7L, "回家前加油", DeferredTask.Status.WAITING))
                .thenReturn(Optional.empty());

        DeferredTask result = service.deferUntilTaskCompleted(
                "拿公司電腦", "回家前加油", null, TaskPriority.NORMAL, 0);

        assertThat(result.getStatus()).isEqualTo(DeferredTask.Status.WAITING);
        assertThat(result.getPredecessorTaskId()).isEqualTo(7L);
        assertThat(result.getTitle()).isEqualTo("回家前加油");
    }

    @Test
    void ambiguousPredecessorNeverGuesses() {
        Task companyLaptop = task(7L, "拿公司電腦");
        Task homeLaptop = task(8L, "拿家裡電腦");
        when(taskService.findOpenTasksMatching("拿電腦"))
                .thenReturn(List.of(companyLaptop, homeLaptop));

        assertThatThrownBy(() -> service.deferUntilTaskCompleted(
                "拿電腦", "加油", null, TaskPriority.NORMAL, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not unique");
    }

    @Test
    void completionActivatesTaskAtConfirmedCompletionTime() {
        DeferredTask waiting = DeferredTask.waitFor(
                7L, "回家前加油", null, TaskPriority.NORMAL, 0, NOW.minusSeconds(60));
        Task activated = task(9L, "回家前加油");
        when(repository.findAllByPredecessorTaskIdAndStatusOrderByIdAsc(
                7L, DeferredTask.Status.WAITING)).thenReturn(List.of(waiting));
        when(taskService.findOpenTasksMatching("回家前加油")).thenReturn(List.of());
        when(taskService.createTask("回家前加油", null, TaskPriority.NORMAL, NOW))
                .thenReturn(activated);

        service.onTaskCompleted(new TaskCompletedEvent(7L, NOW));

        verify(taskService).createTask("回家前加油", null, TaskPriority.NORMAL, NOW);
        assertThat(waiting.getStatus()).isEqualTo(DeferredTask.Status.TRIGGERED);
        assertThat(waiting.getCreatedTaskId()).isEqualTo(9L);
    }

    private static Task task(long id, String title) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getTitle()).thenReturn(title);
        return task;
    }
}
