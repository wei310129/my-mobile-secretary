package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkspaceOwnedEntityTest {

    private boolean previousFallback;

    @BeforeEach
    void setUp() {
        previousFallback = WorkspaceContextHolder.isLegacyFallbackEnabled();
        WorkspaceContextHolder.clear();
        WorkspaceContextHolder.configureLegacyFallback(false);
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clear();
        WorkspaceContextHolder.configureLegacyFallback(previousFallback);
    }

    @Test
    void persistCallbackAssignsImmutableOwnership() {
        WorkspaceContext context = context();
        TestEntity entity = new TestEntity();

        WorkspaceContextHolder.runWith(context, entity::prepare);

        assertThat(entity.getWorkspaceId()).isEqualTo(context.workspaceId());
        assertThat(entity.getCreatedByUserId()).isEqualTo(context.actorId());
    }

    @Test
    void detachedEntityCannotCrossWorkspaceBoundary() {
        TestEntity entity = new TestEntity();
        WorkspaceContextHolder.runWith(context(), entity::prepare);

        assertThatThrownBy(() -> WorkspaceContextHolder.runWith(context(), entity::prepare))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("different workspace");
    }

    @Test
    void authenticationScopeCannotWriteBusinessEntity() {
        TestEntity entity = new TestEntity();

        assertThatThrownBy(() -> {
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.openAuthentication()) {
                entity.prepare();
            }
        }).isInstanceOf(SecurityException.class)
                .hasMessageContaining("Authentication scope");
    }

    private static WorkspaceContext context() {
        return new WorkspaceContext(UUID.randomUUID(), UUID.randomUUID(), WorkspaceChannel.TEST);
    }

    private static final class TestEntity extends WorkspaceOwnedEntity {
        void prepare() {
            applyWorkspaceOwnership();
        }
    }
}
