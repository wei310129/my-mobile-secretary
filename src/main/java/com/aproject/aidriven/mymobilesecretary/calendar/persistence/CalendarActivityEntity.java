package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "calendar_activity")
public class CalendarActivityEntity extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Column(name = "plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Column(nullable = false, length = 200)
    private String title;

    @Embedded
    private CalendarPlacementEmbeddable placement;

    @Column(length = 80)
    private String category;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CalendarActivityEntity() {}

    private CalendarActivityEntity(
            UUID id,
            UUID planId,
            String title,
            CalendarPlacement placement,
            String category,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.planId = Objects.requireNonNull(planId, "planId");
        if (title == null || title.isBlank() || title.strip().length() > 200) {
            throw new IllegalArgumentException("Activity title must contain 1 to 200 characters");
        }
        this.title = title.strip();
        this.placement = CalendarPlacementEmbeddable.from(placement);
        this.category = category;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = createdAt;
    }

    public static CalendarActivityEntity create(
            UUID id,
            UUID planId,
            String title,
            CalendarPlacement placement,
            Instant createdAt) {
        return new CalendarActivityEntity(id, planId, title, placement, null, createdAt);
    }

    public static CalendarActivityEntity create(
            UUID id,
            UUID planId,
            String title,
            CalendarPlacement placement,
            String category,
            Instant createdAt) {
        return new CalendarActivityEntity(id, planId, title, placement, category, createdAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return planId;
    }
}
