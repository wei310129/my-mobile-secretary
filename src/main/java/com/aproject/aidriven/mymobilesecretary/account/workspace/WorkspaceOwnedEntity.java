package com.aproject.aidriven.mymobilesecretary.account.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Common immutable ownership metadata for every tenant-owned business entity. */
@MappedSuperclass
public abstract class WorkspaceOwnedEntity {

    @TenantId
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    protected WorkspaceOwnedEntity() {
    }

    @PrePersist
    @PreUpdate
    protected final void applyWorkspaceOwnership() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (context.isAuthentication()) {
            throw new SecurityException("Authentication scope cannot write workspace data");
        }
        if (workspaceId == null) {
            workspaceId = context.workspaceId();
        } else if (!workspaceId.equals(context.workspaceId())) {
            throw new SecurityException("Entity belongs to a different workspace");
        }
        if (createdByUserId == null) {
            createdByUserId = context.actorId();
        }
        onWorkspaceOwnershipApplied(context);
    }

    /** Hook for tenant-owned collection rows that cannot inherit this mapped superclass. */
    protected void onWorkspaceOwnershipApplied(WorkspaceContext context) {
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }
}
