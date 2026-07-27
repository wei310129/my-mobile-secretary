package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "calendar_plan")
public class CalendarPlanEntity extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 200)
    private String title;

    @Embedded
    private CalendarPlacementEmbeddable placement;

    @Column(name = "creation_request_hash", length = 64, updatable = false)
    private String creationRequestHash;

    @Column(name = "creation_payload_hash", length = 64, updatable = false)
    private String creationPayloadHash;

    @Column(length = 80)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CalendarPlanStatus status;

    private Instant canceledAt;

    private Instant archivedAt;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CalendarPlanEntity() {}

    private CalendarPlanEntity(
            UUID id,
            String title,
            CalendarPlacement placement,
            String requestHash,
            String payloadHash,
            String category,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.title = requireTitle(title);
        this.placement = CalendarPlacementEmbeddable.from(placement);
        creationRequestHash = requestHash;
        creationPayloadHash = payloadHash;
        this.category = category;
        status = CalendarPlanStatus.ACTIVE;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = createdAt;
    }

    public static CalendarPlanEntity create(
            UUID id, String title, CalendarPlacement placement, Instant createdAt) {
        return new CalendarPlanEntity(id, title, placement, null, null, null, createdAt);
    }

    public static CalendarPlanEntity createForCommand(
            UUID id,
            String title,
            CalendarPlacement placement,
            String requestHash,
            String payloadHash,
            String category,
            Instant createdAt) {
        return new CalendarPlanEntity(
                id, title, placement, requestHash, payloadHash, category, createdAt);
    }

    public void rename(String nextTitle, Instant now) {
        requireActive();
        title = requireTitle(nextTitle);
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public void requireActiveForMutation() {
        requireActive();
    }

    public void changeCategory(String nextCategory, long expectedRevision, Instant now) {
        requireActive();
        requireRevision(expectedRevision);
        category = nextCategory;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public CalendarPlacement toPlacement() {
        return placement.toDomain();
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Long getVersion() {
        return version;
    }

    public String getCreationRequestHash() {
        return creationRequestHash;
    }

    public String getCreationPayloadHash() {
        return creationPayloadHash;
    }

    public String getCategory() {
        return category;
    }

    public CalendarPlanStatus getStatus() {
        return status;
    }

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getRevision() {
        return version == null ? 1 : version + 1;
    }

    private static String requireTitle(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 200) {
            throw new IllegalArgumentException("Calendar title must contain 1 to 200 characters");
        }
        return value.strip();
    }

    private void requireActive() {
        if (status != CalendarPlanStatus.ACTIVE) {
            throw new com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException(
                    "CALENDAR_PLAN_NOT_ACTIVE",
                    "Only an active calendar plan can be changed");
        }
    }

    private void requireRevision(long expectedRevision) {
        if (getRevision() != expectedRevision) {
            throw new com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar plan changed; reload it before revising");
        }
    }
}
