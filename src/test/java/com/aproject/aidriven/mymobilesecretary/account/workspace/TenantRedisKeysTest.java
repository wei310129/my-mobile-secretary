package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantRedisKeysTest {

    @AfterEach
    void clearContext() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void legacyKeyRemainsCompatibleAndNewWorkspacesAreSeparated() {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                LegacyAccountIds.USER_ID, LegacyAccountIds.WORKSPACE_ID, WorkspaceChannel.TEST))) {
            assertThat(TenantRedisKeys.current("pending:prompt:last"))
                    .isEqualTo("pending:prompt:last");
        }

        UUID workspaceId = UUID.randomUUID();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(new WorkspaceContext(
                UUID.randomUUID(), workspaceId, WorkspaceChannel.TEST))) {
            assertThat(TenantRedisKeys.current("pending:prompt:last"))
                    .isEqualTo("ws:{" + workspaceId + "}:pending:prompt:last");
        }
    }
}
