package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import java.time.Duration;
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
@Table(name = "calendar_reminder_rule")
public class CalendarReminderRuleEntity extends WorkspaceOwnedEntity {

    enum RuleKind {
        RELATIVE,
        ABSOLUTE
    }

    enum Status {
        ACTIVE,
        REVIEW_REQUIRED,
        ACKNOWLEDGED,
        CANCELED
    }

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "node_id", nullable = false, updatable = false)
    private UUID nodeId;

    @Column(name = "source_created_by_user_id", nullable = false, updatable = false)
    private UUID sourceCreatedByUserId;

    @Column(name = "personal_projection_snapshot_id", updatable = false)
    private UUID personalProjectionSnapshotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_kind", nullable = false, updatable = false, length = 30)
    private CalendarReminderOwnerKind ownerKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_kind", nullable = false, updatable = false, length = 20)
    private RuleKind ruleKind;

    @Column(name = "offset_seconds", updatable = false)
    private Long offsetSeconds;

    @Column(name = "absolute_fire_at", updatable = false)
    private Instant absoluteFireAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, updatable = false, length = 30)
    private CalendarReminderDeliveryMode deliveryMode;

    @Column(name = "ack_interval_seconds", updatable = false)
    private Long ackIntervalSeconds;

    @Column(name = "max_alerts", updatable = false)
    private Integer maxAlerts;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_channel", updatable = false, length = 30)
    private NotificationChannel preferredChannel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(nullable = false)
    private long revision;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CalendarReminderRuleEntity() {}

    private CalendarReminderRuleEntity(
            UUID id,
            UUID planId,
            UUID nodeId,
            UUID sourceCreatedByUserId,
            UUID personalProjectionSnapshotId,
            CalendarReminderOwnerKind ownerKind,
            RuleKind ruleKind,
            Long offsetSeconds,
            Instant absoluteFireAt,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sourceCreatedByUserId =
                Objects.requireNonNull(
                        sourceCreatedByUserId,
                        "sourceCreatedByUserId");
        this.personalProjectionSnapshotId =
                personalProjectionSnapshotId;
        this.ownerKind = Objects.requireNonNull(ownerKind, "ownerKind");
        this.ruleKind = Objects.requireNonNull(ruleKind, "ruleKind");
        this.offsetSeconds = offsetSeconds;
        this.absoluteFireAt = absoluteFireAt;
        this.deliveryMode = Objects.requireNonNull(deliveryMode, "deliveryMode");
        if (deliveryMode == CalendarReminderDeliveryMode.ACK_REQUIRED) {
            if (ackInterval == null
                    || ackInterval.isZero()
                    || ackInterval.isNegative()
                    || maxAlerts == null
                    || maxAlerts < 2
                    || maxAlerts > 20) {
                throw new IllegalArgumentException(
                        "ACK interval and 2 to 20 alerts are required");
            }
            ackIntervalSeconds = ackInterval.getSeconds();
            this.maxAlerts = maxAlerts;
        } else if (ackInterval != null || maxAlerts != null) {
            throw new IllegalArgumentException("One-time alerts cannot have ACK escalation");
        }
        this.preferredChannel = preferredChannel;
        status = Status.ACTIVE;
        revision = 1;
        createdAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    static CalendarReminderRuleEntity relative(
            UUID id,
            UUID planId,
            UUID nodeId,
            UUID sourceCreatedByUserId,
            UUID personalProjectionSnapshotId,
            CalendarReminderOwnerKind ownerKind,
            Duration offset,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel,
            Instant now) {
        Objects.requireNonNull(offset, "offset");
        if (offset.isPositive()) {
            throw new IllegalArgumentException("Relative offset cannot be positive");
        }
        return new CalendarReminderRuleEntity(
                id,
                planId,
                nodeId,
                sourceCreatedByUserId,
                personalProjectionSnapshotId,
                ownerKind,
                RuleKind.RELATIVE,
                offset.getSeconds(),
                null,
                deliveryMode,
                ackInterval,
                maxAlerts,
                preferredChannel,
                now);
    }

    static CalendarReminderRuleEntity absolute(
            UUID id,
            UUID planId,
            UUID nodeId,
            UUID sourceCreatedByUserId,
            UUID personalProjectionSnapshotId,
            CalendarReminderOwnerKind ownerKind,
            Instant fireAt,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel,
            Instant now) {
        return new CalendarReminderRuleEntity(
                id,
                planId,
                nodeId,
                sourceCreatedByUserId,
                personalProjectionSnapshotId,
                ownerKind,
                RuleKind.ABSOLUTE,
                null,
                Objects.requireNonNull(fireAt, "fireAt"),
                deliveryMode,
                ackInterval,
                maxAlerts,
                preferredChannel,
                now);
    }

    Instant scheduleFrom(Instant nodeTime) {
        return ruleKind == RuleKind.RELATIVE
                ? nodeTime.plusSeconds(offsetSeconds)
                : absoluteFireAt;
    }

    void requireReview(Instant now) {
        status = Status.REVIEW_REQUIRED;
        revision++;
        updatedAt = now;
    }

    void acknowledge(Instant now) {
        if (deliveryMode != CalendarReminderDeliveryMode.ACK_REQUIRED
                || status != Status.ACTIVE) {
            throw new IllegalStateException("Reminder is not awaiting acknowledgement");
        }
        status = Status.ACKNOWLEDGED;
        revision++;
        updatedAt = now;
    }

    void cancel(Instant now) {
        if (status == Status.CANCELED) {
            return;
        }
        status = Status.CANCELED;
        revision++;
        updatedAt = now;
    }

    boolean isActive() {
        return status == Status.ACTIVE;
    }

    boolean isRelative() {
        return ruleKind == RuleKind.RELATIVE;
    }

    boolean needsAckAfter(int sequenceNumber) {
        return deliveryMode == CalendarReminderDeliveryMode.ACK_REQUIRED
                && sequenceNumber + 1 < maxAlerts;
    }

    Instant nextAckAt(Instant current) {
        return current.plusSeconds(ackIntervalSeconds);
    }

    public UUID getId() {
        return id;
    }

    UUID getPlanId() {
        return planId;
    }

    UUID getNodeId() {
        return nodeId;
    }

    UUID getSourceCreatedByUserId() {
        return sourceCreatedByUserId;
    }

    UUID getPersonalProjectionSnapshotId() {
        return personalProjectionSnapshotId;
    }

    CalendarReminderOwnerKind getOwnerKind() {
        return ownerKind;
    }

    CalendarReminderDeliveryMode getDeliveryMode() {
        return deliveryMode;
    }

    NotificationChannel getPreferredChannel() {
        return preferredChannel;
    }

    long getRevision() {
        return revision;
    }
}
