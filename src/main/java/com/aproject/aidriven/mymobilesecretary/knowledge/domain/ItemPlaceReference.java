package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Tenant-owned row in the item_place collection table. */
@Embeddable
public class ItemPlaceReference {

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    protected ItemPlaceReference() {
    }

    private ItemPlaceReference(Long placeId) {
        this.placeId = Objects.requireNonNull(placeId, "placeId");
    }

    static ItemPlaceReference of(Long placeId) {
        return new ItemPlaceReference(placeId);
    }

    void applyOwnership(WorkspaceContext context) {
        if (workspaceId == null) {
            workspaceId = context.workspaceId();
        } else if (!workspaceId.equals(context.workspaceId())) {
            throw new SecurityException("Item place belongs to a different workspace");
        }
        if (createdByUserId == null) {
            createdByUserId = context.actorId();
        }
    }

    Long placeId() {
        return placeId;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ItemPlaceReference that
                && Objects.equals(placeId, that.placeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(placeId);
    }
}
