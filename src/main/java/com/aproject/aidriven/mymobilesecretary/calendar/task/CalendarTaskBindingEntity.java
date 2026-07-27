package com.aproject.aidriven.mymobilesecretary.calendar.task;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "calendar_task_binding")
public class CalendarTaskBindingEntity extends WorkspaceOwnedEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CalendarTaskTarget.TargetKind targetKind;

    @Column(nullable = false)
    private UUID planId;

    private UUID activityId;
    private UUID nodeId;

    @Column(nullable = false, length = 64)
    private String creationRequestHash;

    @Column(nullable = false, length = 64)
    private String creationPayloadHash;

    @Column(nullable = false)
    private Instant createdAt;

    protected CalendarTaskBindingEntity() {}

    public Long getTaskId() {
        return taskId;
    }

    public CalendarTaskTarget.TargetKind getTargetKind() {
        return targetKind;
    }

    public UUID getPlanId() {
        return planId;
    }

    public UUID getActivityId() {
        return activityId;
    }

    public UUID getNodeId() {
        return nodeId;
    }

    public String getCreationPayloadHash() {
        return creationPayloadHash;
    }
}
