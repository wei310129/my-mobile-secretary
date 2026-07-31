package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.TimeExpression;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
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
@Table(name = "calendar_time_node")
public class CalendarTimeNodeEntity extends WorkspaceOwnedEntity {

    private enum ExpressionKind {
        ABSOLUTE,
        OWNER_START_OFFSET,
        OWNER_END_OFFSET,
        NODE_OFFSET
    }

    private enum CancellationStatus {
        ACTIVE,
        CANCELED
    }

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "activity_id", updatable = false)
    private UUID activityId;

    @Column(name = "node_key", nullable = false, updatable = false, length = 100)
    private String nodeKey;

    @Column(nullable = false, length = 200)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "expression_kind", nullable = false, length = 30)
    private ExpressionKind expressionKind;

    @Column(name = "absolute_time")
    private Instant absoluteTime;

    @Column(name = "offset_seconds")
    private Long offsetSeconds;

    @Column(name = "base_node_key", length = 100)
    private String baseNodeKey;

    @Column(name = "resolved_time")
    private Instant resolvedTime;

    @Column(name = "location_label", length = 200)
    private String locationLabel;

    private Double latitude;

    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Criticality criticality;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Adjustability adjustability;

    @Version
    private Long version;

    @Column(nullable = false)
    private long revision = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_status", nullable = false, length = 20)
    private CancellationStatus cancellationStatus = CancellationStatus.ACTIVE;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CalendarTimeNodeEntity() {}

    private CalendarTimeNodeEntity(
            UUID id,
            UUID planId,
            UUID activityId,
            CalendarTimeNode node,
            Instant resolvedTime,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.activityId = activityId;
        nodeKey = node.id();
        label = node.label();
        criticality = node.criticality();
        adjustability = node.adjustability();
        applyExpression(node.expression());
        this.resolvedTime = resolvedTime;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = createdAt;
    }

    public static CalendarTimeNodeEntity create(
            UUID id, UUID planId, UUID activityId, CalendarTimeNode node, Instant createdAt) {
        Instant resolved = node.expression() instanceof TimeExpression.Absolute absolute
                ? absolute.time()
                : null;
        return new CalendarTimeNodeEntity(
                id, planId, activityId, node, resolved, createdAt);
    }

    public static CalendarTimeNodeEntity create(
            UUID id,
            UUID planId,
            UUID activityId,
            CalendarTimeNode node,
            Instant resolvedTime,
            Instant createdAt) {
        return new CalendarTimeNodeEntity(
                id, planId, activityId, node, resolvedTime, createdAt);
    }

    public static CalendarTimeNodeEntity create(
            UUID id,
            UUID planId,
            UUID activityId,
            CalendarTimeNode node,
            Instant resolvedTime,
            CalendarLocation location,
            Instant createdAt) {
        CalendarTimeNodeEntity entity =
                new CalendarTimeNodeEntity(
                        id, planId, activityId, node, resolvedTime, createdAt);
        if (location != null) {
            entity.locationLabel = location.label();
            entity.latitude = location.latitude();
            entity.longitude = location.longitude();
        }
        return entity;
    }

    private void applyExpression(TimeExpression expression) {
        switch (expression) {
            case TimeExpression.Absolute absolute -> {
                expressionKind = ExpressionKind.ABSOLUTE;
                absoluteTime = absolute.time();
            }
            case TimeExpression.OwnerRelative relative -> {
                expressionKind = relative.boundary() == CalendarTimeNode.OwnerBoundary.START
                        ? ExpressionKind.OWNER_START_OFFSET
                        : ExpressionKind.OWNER_END_OFFSET;
                offsetSeconds = relative.offset().getSeconds();
            }
            case TimeExpression.NodeRelative relative -> {
                expressionKind = ExpressionKind.NODE_OFFSET;
                offsetSeconds = relative.offset().getSeconds();
                baseNodeKey = relative.baseNodeId();
            }
        }
    }

    public void moveOrdinarily(Instant time, long expectedRevision, Instant now) {
        if (adjustability == Adjustability.LOCKED) {
            throw new BusinessException(
                    "LOCKED_NODE_REQUIRES_REVISION",
                    "A locked calendar node requires an explicit revision");
        }
        reviseAbsolute(time, expectedRevision, now);
    }

    public void reviseAbsolute(Instant time, long expectedRevision, Instant now) {
        requireActive();
        if (revision != expectedRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar node changed; reload it before revising");
        }
        expressionKind = ExpressionKind.ABSOLUTE;
        absoluteTime = Objects.requireNonNull(time, "time");
        resolvedTime = absoluteTime;
        offsetSeconds = null;
        baseNodeKey = null;
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void reviseLocation(
            CalendarLocation location, long expectedRevision, Instant now) {
        requireActive();
        if (revision != expectedRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar node changed; reload it before revising");
        }
        locationLabel = location.label();
        latitude = location.latitude();
        longitude = location.longitude();
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void cancel(long expectedRevision, Instant now) {
        requireActive();
        if (revision != expectedRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar node changed; reload it before canceling");
        }
        Instant canceled = Objects.requireNonNull(now, "now");
        cancellationStatus = CancellationStatus.CANCELED;
        canceledAt = canceled;
        revision++;
        updatedAt = canceled;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public String getLabel() {
        return label;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }

    public Criticality getCriticality() {
        return criticality;
    }

    public Instant getResolvedTime() {
        return resolvedTime;
    }

    public CalendarLocation getLocation() {
        return locationLabel == null
                ? null
                : new CalendarLocation(locationLabel, latitude, longitude);
    }

    public Adjustability getAdjustability() {
        return adjustability;
    }

    public long getRevision() {
        return revision;
    }

    public Instant getAbsoluteTime() {
        return absoluteTime;
    }

    public boolean isCanceled() {
        return cancellationStatus == CancellationStatus.CANCELED;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    private void requireActive() {
        if (isCanceled()) {
            throw new BusinessException(
                    "CALENDAR_NODE_ALREADY_CANCELED",
                    "A canceled calendar node cannot be revised");
        }
    }
}
