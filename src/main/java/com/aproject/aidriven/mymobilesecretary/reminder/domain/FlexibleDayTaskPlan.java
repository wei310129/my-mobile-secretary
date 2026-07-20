package com.aproject.aidriven.mymobilesecretary.reminder.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A date-scoped task that does not block the calendar and has one deterministic reminder slot. */
@Entity
public class FlexibleDayTaskPlan extends WorkspaceOwnedEntity {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long taskId;
    @Column(nullable = false)
    private LocalDate targetDate;
    @Column(nullable = false)
    private Instant remindAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SourceKind sourceKind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected FlexibleDayTaskPlan() {
    }

    public static FlexibleDayTaskPlan schedule(Long taskId, LocalDate targetDate,
                                               Instant remindAt, SourceKind sourceKind,
                                               Instant now) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("task id is required");
        }
        if (targetDate == null || remindAt == null || now == null) {
            throw new IllegalArgumentException("target date, reminder and current time are required");
        }
        if (remindAt.isBefore(now.plusSeconds(300))) {
            throw new IllegalArgumentException("reminder must be at least five minutes in the future");
        }
        if (remindAt.atZone(TAIPEI).toLocalDate().isAfter(targetDate)) {
            throw new IllegalArgumentException("reminder cannot be after the target date");
        }
        FlexibleDayTaskPlan plan = new FlexibleDayTaskPlan();
        plan.taskId = taskId;
        plan.targetDate = targetDate;
        plan.remindAt = remindAt;
        plan.sourceKind = sourceKind == null ? SourceKind.USER_REQUEST : sourceKind;
        plan.status = Status.SCHEDULED;
        plan.createdAt = now;
        plan.updatedAt = now;
        return plan;
    }

    public void complete(Instant now) {
        if (status == Status.SCHEDULED) {
            status = Status.COMPLETED;
            updatedAt = now;
        }
    }

    public void cancel(Instant now) {
        if (status == Status.SCHEDULED) {
            status = Status.CANCELED;
            updatedAt = now;
        }
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public LocalDate getTargetDate() { return targetDate; }
    public Instant getRemindAt() { return remindAt; }
    public SourceKind getSourceKind() { return sourceKind; }
    public Status getStatus() { return status; }

    public enum SourceKind { USER_REQUEST, PAYMENT_NOTICE }
    public enum Status { SCHEDULED, COMPLETED, CANCELED }
}
