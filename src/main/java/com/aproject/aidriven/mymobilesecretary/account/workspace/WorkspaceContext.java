package com.aproject.aidriven.mymobilesecretary.account.workspace;

import java.util.Objects;
import java.util.UUID;

public record WorkspaceContext(UUID actorId, UUID workspaceId, WorkspaceChannel channel,
                               String conversationAdapterNamespace,
                               String conversationScopeToken) {

    public static final UUID NIL_ID = new UUID(0L, 0L);

    public WorkspaceContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(channel, "channel");
        conversationAdapterNamespace = requireScopePart(
                conversationAdapterNamespace, "conversation adapter namespace");
        conversationScopeToken = requireScopePart(conversationScopeToken, "conversation scope token");
    }

    public WorkspaceContext(UUID actorId, UUID workspaceId, WorkspaceChannel channel) {
        this(actorId, workspaceId, channel, "legacy", "legacy-default");
    }

    /**
     * Scope used only while resolving an external identity and membership.
     * NIL is not a persisted workspace, so accidental tenant queries return no business rows.
     */
    public static WorkspaceContext authentication() {
        return new WorkspaceContext(NIL_ID, NIL_ID, WorkspaceChannel.AUTHENTICATION);
    }

    /** Scope for trusted global retention and infrastructure maintenance only. */
    public static WorkspaceContext system() {
        return new WorkspaceContext(NIL_ID, NIL_ID, WorkspaceChannel.SYSTEM);
    }

    public boolean isAuthentication() {
        return channel == WorkspaceChannel.AUTHENTICATION;
    }

    public boolean isSystem() {
        return channel == WorkspaceChannel.SYSTEM;
    }

    public boolean isTenantScope() {
        return !isAuthentication() && !isSystem();
    }

    private static String requireScopePart(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }
}
