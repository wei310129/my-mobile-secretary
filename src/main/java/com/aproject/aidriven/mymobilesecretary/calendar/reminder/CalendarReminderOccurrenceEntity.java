package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "calendar_reminder_occurrence")
public class CalendarReminderOccurrenceEntity extends WorkspaceOwnedEntity {

    enum Status {
        PENDING,
        ENQUEUED,
        CANCELED
    }

    @Id
    private UUID id;

    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "node_id", nullable = false, updatable = false)
    private UUID nodeId;

    @Column(name = "source_created_by_user_id", nullable = false, updatable = false)
    private UUID sourceCreatedByUserId;

    @Column(name = "node_revision", nullable = false, updatable = false)
    private long nodeRevision;

    @Column(name = "rule_revision", nullable = false, updatable = false)
    private long ruleRevision;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CalendarReminderOccurrenceEntity() {}

    private CalendarReminderOccurrenceEntity(
            UUID id,
            CalendarReminderRuleEntity rule,
            long nodeRevision,
            int sequenceNumber,
            Instant scheduledAt,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        ruleId = rule.getId();
        planId = rule.getPlanId();
        nodeId = rule.getNodeId();
        sourceCreatedByUserId = rule.getSourceCreatedByUserId();
        this.nodeRevision = nodeRevision;
        ruleRevision = rule.getRevision();
        this.sequenceNumber = sequenceNumber;
        this.scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
        status = Status.PENDING;
        createdAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    static CalendarReminderOccurrenceEntity pending(
            UUID id,
            CalendarReminderRuleEntity rule,
            long nodeRevision,
            int sequenceNumber,
            Instant scheduledAt,
            Instant now) {
        return new CalendarReminderOccurrenceEntity(
                id, rule, nodeRevision, sequenceNumber, scheduledAt, now);
    }

    void cancel(Instant now) {
        if (status == Status.PENDING) {
            status = Status.CANCELED;
            updatedAt = now;
        }
    }

    void enqueue(Instant now) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("Only pending reminders can be enqueued");
        }
        status = Status.ENQUEUED;
        updatedAt = now;
    }

    void deferUntil(Instant nextTime, Instant now) {
        if (status != Status.PENDING || !nextTime.isAfter(scheduledAt)) {
            throw new IllegalArgumentException("Reminder deferral must move a pending occurrence");
        }
        scheduledAt = nextTime;
        updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getRuleId() {
        return ruleId;
    }

    long getNodeRevision() {
        return nodeRevision;
    }

    int getSequenceNumber() {
        return sequenceNumber;
    }

    Instant getScheduledAt() {
        return scheduledAt;
    }
}
