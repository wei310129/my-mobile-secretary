package com.aproject.aidriven.mymobilesecretary.account.workspace;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

public final class WorkspaceTenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return WorkspaceContextHolder.requireContext().workspaceId();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public boolean isRoot(UUID tenantId) {
        return false;
    }
}
