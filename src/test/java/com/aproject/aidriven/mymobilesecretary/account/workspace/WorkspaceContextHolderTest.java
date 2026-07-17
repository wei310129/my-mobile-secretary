package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkspaceContextHolderTest {

    private boolean previousFallback;

    @BeforeEach
    void disableFallback() {
        previousFallback = WorkspaceContextHolder.isLegacyFallbackEnabled();
        WorkspaceContextHolder.clear();
        WorkspaceContextHolder.configureLegacyFallback(false);
    }

    @AfterEach
    void cleanUp() {
        WorkspaceContextHolder.clear();
        WorkspaceContextHolder.configureLegacyFallback(previousFallback);
    }

    @Test
    void productionModeFailsClosedWithoutScope() {
        assertThatThrownBy(WorkspaceContextHolder::requireContext)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workspace context");
    }

    @Test
    void explicitScopesAreNestableAndRestoreThePreviousActor() {
        WorkspaceContext outer = context(WorkspaceChannel.REST);
        WorkspaceContext inner = context(WorkspaceChannel.BACKGROUND);

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(outer)) {
            assertThat(WorkspaceContextHolder.requireContext()).isEqualTo(outer);
            try (WorkspaceContextHolder.Scope nested = WorkspaceContextHolder.open(inner)) {
                assertThat(WorkspaceContextHolder.requireContext()).isEqualTo(inner);
            }
            assertThat(WorkspaceContextHolder.requireContext()).isEqualTo(outer);
        }

        assertThat(WorkspaceContextHolder.current()).isEmpty();
    }

    @Test
    void localFallbackMustBeExplicitlyEnabled() {
        WorkspaceContextHolder.configureLegacyFallback(true);

        WorkspaceContext context = WorkspaceContextHolder.requireContext();

        assertThat(context.actorId()).isEqualTo(LegacyAccountIds.USER_ID);
        assertThat(context.workspaceId()).isEqualTo(LegacyAccountIds.WORKSPACE_ID);
        assertThat(context.channel()).isEqualTo(WorkspaceChannel.LOCAL);
        assertThat(WorkspaceContextHolder.current()).isEmpty();
    }

    @Test
    void authenticationScopeUsesNonPersistedNilTenant() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.openAuthentication()) {
            WorkspaceContext context = WorkspaceContextHolder.requireContext();
            assertThat(context.isAuthentication()).isTrue();
            assertThat(context.actorId()).isEqualTo(WorkspaceContext.NIL_ID);
            assertThat(context.workspaceId()).isEqualTo(WorkspaceContext.NIL_ID);
            assertThat(new WorkspaceTenantIdentifierResolver().resolveCurrentTenantIdentifier())
                    .isEqualTo(WorkspaceContext.NIL_ID);
        }
    }

    @Test
    void systemScopeIsDistinctFromAuthentication() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                WorkspaceContext.system())) {
            WorkspaceContext context = WorkspaceContextHolder.requireContext();
            assertThat(context.isSystem()).isTrue();
            assertThat(context.isAuthentication()).isFalse();
            assertThat(context.isTenantScope()).isFalse();
            assertThat(context.workspaceId()).isEqualTo(WorkspaceContext.NIL_ID);
        }
    }

    private static WorkspaceContext context(WorkspaceChannel channel) {
        return new WorkspaceContext(UUID.randomUUID(), UUID.randomUUID(), channel);
    }
}
