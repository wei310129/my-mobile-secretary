package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.DeferredTask;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.DeferredTaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists and activates tasks whose creation depends on confirmed predecessor completion. */
@Service
@Transactional
public class DeferredTaskService {

    private final DeferredTaskRepository repository;
    private final TaskService taskService;
    private final Clock clock;

    public DeferredTaskService(
            DeferredTaskRepository repository, TaskService taskService, Clock clock) {
        this.repository = repository;
        this.taskService = taskService;
        this.clock = clock;
    }

    public DeferredTask deferUntilTaskCompleted(
            String predecessorTitle, String title, String description,
            TaskPriority priority, int dueOffsetMinutes) {
        if (predecessorTitle == null || predecessorTitle.isBlank()) {
            throw new IllegalArgumentException("dependent task referenceTitle missing");
        }
        List<Task> matches = taskService.findOpenTasksMatching(predecessorTitle);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException(
                    "dependent task predecessor not found: " + predecessorTitle);
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "dependent task predecessor is not unique: " + predecessorTitle);
        }
        Task predecessor = matches.getFirst();
        return repository.findFirstByPredecessorTaskIdAndTitleIgnoreCaseAndStatus(
                        predecessor.getId(), title, DeferredTask.Status.WAITING)
                .orElseGet(() -> repository.save(DeferredTask.waitFor(
                        predecessor.getId(), title, description, priority,
                        dueOffsetMinutes, Instant.now(clock))));
    }

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        List<DeferredTask> waiting = repository
                .findAllByPredecessorTaskIdAndStatusOrderByIdAsc(
                        event.taskId(), DeferredTask.Status.WAITING);
        for (DeferredTask deferred : waiting) {
            Task activated = existingOpenTask(deferred.getTitle());
            if (activated == null) {
                Instant dueAt = event.completedAt().plus(
                        Duration.ofMinutes(deferred.getDueOffsetMinutes()));
                activated = taskService.createTask(
                        deferred.getTitle(), deferred.getDescription(), deferred.getPriority(), dueAt);
            }
            deferred.markTriggered(activated.getId(), event.completedAt());
        }
    }

    private Task existingOpenTask(String title) {
        return taskService.findOpenTasksMatching(title).stream()
                .filter(task -> task.getTitle().equalsIgnoreCase(title))
                .findFirst()
                .orElse(null);
    }
}
