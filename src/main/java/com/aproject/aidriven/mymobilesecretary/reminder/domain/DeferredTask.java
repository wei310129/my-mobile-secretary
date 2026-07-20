package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A task definition that becomes a real task only after its predecessor is completed. */
@Entity
public class DeferredTask extends WorkspaceOwnedEntity {

    public enum Status {
        WAITING,
        TRIGGERED,
        CANCELED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long predecessorTaskId;

    @Column(nullable = false, length = 200)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TaskPriority priority;

    @Column(nullable = false)
    private int dueOffsetMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    private Long createdTaskId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant triggeredAt;

    protected DeferredTask() {
    }

    private DeferredTask(Long predecessorTaskId, String title, String description,
                         TaskPriority priority, int dueOffsetMinutes, Instant now) {
        if (predecessorTaskId == null) {
            throw new IllegalArgumentException("predecessor task is required");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("deferred task title is required");
        }
        if (dueOffsetMinutes < 0 || dueOffsetMinutes > 10_080) {
            throw new IllegalArgumentException("deferred task offset must be between 0 and 10080 minutes");
        }
        this.predecessorTaskId = predecessorTaskId;
        this.title = title.strip();
        this.description = description == null || description.isBlank() ? null : description.strip();
        this.priority = priority == null ? TaskPriority.NORMAL : priority;
        this.dueOffsetMinutes = dueOffsetMinutes;
        this.status = Status.WAITING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static DeferredTask waitFor(
            Long predecessorTaskId, String title, String description,
            TaskPriority priority, int dueOffsetMinutes, Instant now) {
        return new DeferredTask(predecessorTaskId, title, description,
                priority, dueOffsetMinutes, now);
    }

    public void markTriggered(Long taskId, Instant now) {
        if (status != Status.WAITING) {
            throw new BusinessException("INVALID_DEFERRED_TASK_TRANSITION",
                    "Deferred task %d cannot trigger from %s".formatted(id, status));
        }
        if (taskId == null) {
            throw new IllegalArgumentException("created task id is required");
        }
        status = Status.TRIGGERED;
        createdTaskId = taskId;
        triggeredAt = now;
        updatedAt = now;
    }

    public void cancel(Instant now) {
        if (status != Status.WAITING) {
            throw new BusinessException("INVALID_DEFERRED_TASK_TRANSITION",
                    "Deferred task %d cannot cancel from %s".formatted(id, status));
        }
        status = Status.CANCELED;
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getPredecessorTaskId() {
        return predecessorTaskId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public int getDueOffsetMinutes() {
        return dueOffsetMinutes;
    }

    public Status getStatus() {
        return status;
    }

    public Long getCreatedTaskId() {
        return createdTaskId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }
}
