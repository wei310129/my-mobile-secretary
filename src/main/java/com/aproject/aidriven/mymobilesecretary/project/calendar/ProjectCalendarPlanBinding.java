package com.aproject.aidriven.mymobilesecretary.project.calendar;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "project_calendar_plan_binding")
public class ProjectCalendarPlanBinding extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "calendar_plan_id", nullable = false, updatable = false)
    private UUID planId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectCalendarBindingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProjectCalendarUnlinkReason unlinkReason;

    @Column(nullable = false)
    private long bindingRevision;

    @Column(nullable = false, updatable = false, length = 64)
    private String creationRequestHash;

    @Column(nullable = false, updatable = false, length = 64)
    private String creationPayloadHash;

    @Column(length = 64)
    private String unlinkRequestHash;

    @Column(length = 64)
    private String unlinkPayloadHash;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant unlinkedAt;

    protected ProjectCalendarPlanBinding() {
    }

    public void unlinkExplicitly(
            long expectedRevision,
            String requestHash,
            String payloadHash,
            Instant now) {
        if (status == ProjectCalendarBindingStatus.UNLINKED) {
            requireUnlinkReplay(requestHash, payloadHash);
            return;
        }
        requireRevision(expectedRevision);
        unlink(
                ProjectCalendarUnlinkReason.EXPLICIT,
                requestHash,
                payloadHash,
                now);
    }

    public void unlinkForCalendar(
            ProjectCalendarUnlinkReason reason, Instant now) {
        if (reason == ProjectCalendarUnlinkReason.EXPLICIT) {
            throw new IllegalArgumentException(
                    "Calendar lifecycle requires a calendar unlink reason");
        }
        if (status == ProjectCalendarBindingStatus.UNLINKED) {
            return;
        }
        unlink(reason, null, null, now);
    }

    private void unlink(
            ProjectCalendarUnlinkReason reason,
            String requestHash,
            String payloadHash,
            Instant now) {
        status = ProjectCalendarBindingStatus.UNLINKED;
        unlinkReason = reason;
        unlinkRequestHash = requestHash;
        unlinkPayloadHash = payloadHash;
        unlinkedAt = now;
        updatedAt = now;
        bindingRevision++;
    }

    public void requireCreationReplay(
            UUID expectedProjectId,
            UUID expectedPlanId,
            String expectedPayloadHash) {
        if (!projectId.equals(expectedProjectId)
                || !planId.equals(expectedPlanId)
                || !creationPayloadHash.equals(expectedPayloadHash)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for different Project Calendar ownership");
        }
    }

    private void requireUnlinkReplay(String requestHash, String payloadHash) {
        if (unlinkReason != ProjectCalendarUnlinkReason.EXPLICIT
                || !requestHash.equals(unlinkRequestHash)
                || !payloadHash.equals(unlinkPayloadHash)) {
            throw new BusinessException(
                    "PROJECT_CALENDAR_BINDING_REVISION_CONFLICT",
                    "Project Calendar ownership is already unlinked");
        }
    }

    private void requireRevision(long expectedRevision) {
        if (expectedRevision <= 0 || bindingRevision != expectedRevision) {
            throw new BusinessException(
                    "PROJECT_CALENDAR_BINDING_REVISION_CONFLICT",
                    "Project Calendar ownership changed; reload before unlinking");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public ProjectCalendarBindingStatus getStatus() {
        return status;
    }

    public ProjectCalendarUnlinkReason getUnlinkReason() {
        return unlinkReason;
    }

    public long getBindingRevision() {
        return bindingRevision;
    }

    public String getCreationPayloadHash() {
        return creationPayloadHash;
    }

    public Instant getUnlinkedAt() {
        return unlinkedAt;
    }
}
