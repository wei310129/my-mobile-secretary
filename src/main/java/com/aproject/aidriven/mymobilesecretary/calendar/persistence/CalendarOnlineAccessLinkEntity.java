package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "calendar_online_access_link")
public class CalendarOnlineAccessLinkEntity extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(name = "activity_id", updatable = false)
    private UUID activityId;

    @Column(name = "node_id", updatable = false)
    private UUID nodeId;

    @Column(name = "normalized_uri", nullable = false)
    private String normalizedUri;

    @Column(name = "safe_host", nullable = false, length = 253)
    private String safeHost;

    @Column(length = 120)
    private String label;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CalendarOnlineAccessLinkEntity() {}

    private CalendarOnlineAccessLinkEntity(
            UUID id,
            UUID planId,
            UUID activityId,
            UUID nodeId,
            String normalizedUri,
            String safeHost,
            String label,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.planId = Objects.requireNonNull(planId, "planId");
        this.activityId = activityId;
        this.nodeId = nodeId;
        this.normalizedUri = Objects.requireNonNull(normalizedUri, "normalizedUri");
        this.safeHost = Objects.requireNonNull(safeHost, "safeHost");
        this.label = label;
        createdAt = Objects.requireNonNull(now, "now");
        updatedAt = now;
    }

    public static CalendarOnlineAccessLinkEntity forPlan(
            UUID id,
            UUID planId,
            String normalizedUri,
            String safeHost,
            String label,
            Instant now) {
        return new CalendarOnlineAccessLinkEntity(
                id, planId, null, null, normalizedUri, safeHost, label, now);
    }

    public String getSafeHost() {
        return safeHost;
    }
}
