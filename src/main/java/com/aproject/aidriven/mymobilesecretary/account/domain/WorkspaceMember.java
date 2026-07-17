package com.aproject.aidriven.mymobilesecretary.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "workspace_member", uniqueConstraints = @UniqueConstraint(
        name = "uq_workspace_member_workspace_user", columnNames = {"workspace_id", "user_id"}))
public class WorkspaceMember {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID workspaceId;

    @Column(nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceRole role;

    @Column(nullable = false)
    private UUID createdByUserId;

    @Column(nullable = false)
    private Instant joinedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected WorkspaceMember() {
    }

    private WorkspaceMember(UUID id, UUID workspaceId, UUID userId, WorkspaceRole role,
                            UUID createdByUserId, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.role = Objects.requireNonNull(role, "role");
        this.createdByUserId = Objects.requireNonNull(createdByUserId, "createdByUserId");
        this.joinedAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public static WorkspaceMember create(UUID workspaceId, UUID userId, WorkspaceRole role,
                                         UUID createdByUserId, Instant now) {
        return new WorkspaceMember(UUID.randomUUID(), workspaceId, userId, role, createdByUserId, now);
    }

    public void changeRole(WorkspaceRole role, Instant now) {
        this.role = Objects.requireNonNull(role, "role");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public boolean grants(WorkspaceRole requiredRole) {
        return role.grants(requiredRole);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public WorkspaceRole getRole() {
        return role;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
