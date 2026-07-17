package com.aproject.aidriven.mymobilesecretary.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditService;
import com.aproject.aidriven.mymobilesecretary.account.security.idempotency.IdempotencyService;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceBackgroundRunner;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentTraceRetentionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetentionCoordinatorTest {

    @AfterEach
    void clearWorkspaceContext() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void purgesEveryPrivacyStore() {
        IntentTraceRetentionService trace = mock(IntentTraceRetentionService.class);
        LineMessageLogService line = mock(LineMessageLogService.class);
        SecurityAuditService audit = mock(SecurityAuditService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        WorkspaceBackgroundRunner runner = runnerFor(List.of(backgroundContext()));
        when(trace.purgeExpired()).thenAnswer(ignored -> {
            assertSystemScope();
            return new IntentTraceRetentionService.PurgeResult(3, 2, Instant.EPOCH);
        });
        when(line.purgeExpired()).thenAnswer(ignored -> {
            assertThat(WorkspaceContextHolder.requireContext().channel())
                    .isEqualTo(WorkspaceChannel.BACKGROUND);
            return 4L;
        });
        when(audit.purgeExpired()).thenAnswer(ignored -> {
            assertSystemScope();
            return 5L;
        });
        when(idempotency.purgeExpired()).thenAnswer(ignored -> {
            assertSystemScope();
            return 6;
        });

        RetentionCoordinator.CleanupResult result =
                new RetentionCoordinator(trace, line, audit, idempotency, runner).cleanup();

        assertThat(result).isEqualTo(new RetentionCoordinator.CleanupResult(3, 2, 4, 5, 6));
        verify(runner, times(3)).runSystem(any());
        verify(runner).forEachWorkspace(eq("line-message-retention"), any());
    }

    @Test
    void oneStoreFailureDoesNotBlockTheRemainingPurges() {
        IntentTraceRetentionService trace = mock(IntentTraceRetentionService.class);
        LineMessageLogService line = mock(LineMessageLogService.class);
        SecurityAuditService audit = mock(SecurityAuditService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        WorkspaceBackgroundRunner runner = runnerFor(List.of(backgroundContext()));
        when(trace.purgeExpired()).thenThrow(new IllegalStateException("unavailable"));

        RetentionCoordinator.CleanupResult result =
                new RetentionCoordinator(trace, line, audit, idempotency, runner).cleanup();

        assertThat(result.rawTracesCleared()).isEqualTo(-1);
        assertThat(result.traceSummariesDeleted()).isEqualTo(-1);
        verify(line).purgeExpired();
        verify(audit).purgeExpired();
        verify(idempotency).purgeExpired();
    }

    @Test
    void lineRetentionRunsForTwoWorkspacesAndSumsDeletedRows() {
        IntentTraceRetentionService trace = mock(IntentTraceRetentionService.class);
        LineMessageLogService line = mock(LineMessageLogService.class);
        SecurityAuditService audit = mock(SecurityAuditService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        WorkspaceContext first = backgroundContext();
        WorkspaceContext second = backgroundContext();
        WorkspaceBackgroundRunner runner = runnerFor(List.of(first, second));
        when(trace.purgeExpired()).thenReturn(new IntentTraceRetentionService.PurgeResult(
                0, 0, Instant.EPOCH));
        List<UUID> observedWorkspaces = new ArrayList<>();
        when(line.purgeExpired()).thenAnswer(ignored -> {
            UUID workspaceId = WorkspaceContextHolder.requireContext().workspaceId();
            observedWorkspaces.add(workspaceId);
            return workspaceId.equals(first.workspaceId()) ? 4L : 7L;
        });

        RetentionCoordinator.CleanupResult result =
                new RetentionCoordinator(trace, line, audit, idempotency, runner).cleanup();

        assertThat(result.lineMessagesDeleted()).isEqualTo(11L);
        assertThat(observedWorkspaces)
                .containsExactly(first.workspaceId(), second.workspaceId());
        verify(line, times(2)).purgeExpired();
    }

    @Test
    void failedLineWorkspaceDoesNotDiscardTheSuccessfulWorkspacePurge() {
        IntentTraceRetentionService trace = mock(IntentTraceRetentionService.class);
        LineMessageLogService line = mock(LineMessageLogService.class);
        SecurityAuditService audit = mock(SecurityAuditService.class);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        WorkspaceBackgroundRunner runner = runnerFor(List.of(
                backgroundContext(), backgroundContext()));
        when(trace.purgeExpired()).thenReturn(new IntentTraceRetentionService.PurgeResult(
                0, 0, Instant.EPOCH));
        when(line.purgeExpired())
                .thenThrow(new IllegalStateException("one tenant unavailable"))
                .thenReturn(7L);

        RetentionCoordinator.CleanupResult result =
                new RetentionCoordinator(trace, line, audit, idempotency, runner).cleanup();

        assertThat(result.lineMessagesDeleted()).isEqualTo(7L);
        verify(line, times(2)).purgeExpired();
        verify(audit).purgeExpired();
        verify(idempotency).purgeExpired();
    }

    private static WorkspaceBackgroundRunner runnerFor(List<WorkspaceContext> contexts) {
        WorkspaceBackgroundRunner runner = mock(WorkspaceBackgroundRunner.class);
        when(runner.runSystem(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                    WorkspaceContext.system())) {
                return operation.get();
            }
        });
        when(runner.forEachWorkspace(eq("line-message-retention"), any()))
                .thenAnswer(invocation -> {
                    Consumer<WorkspaceContext> operation = invocation.getArgument(1);
                    int succeeded = 0;
                    int failed = 0;
                    for (WorkspaceContext context : contexts) {
                        try (WorkspaceContextHolder.Scope ignored =
                                     WorkspaceContextHolder.open(context)) {
                            operation.accept(context);
                            succeeded++;
                        } catch (RuntimeException exception) {
                            failed++;
                        }
                    }
                    return new WorkspaceBackgroundRunner.RunSummary(
                            contexts.size(), succeeded, failed, 0);
                });
        return runner;
    }

    private static WorkspaceContext backgroundContext() {
        return new WorkspaceContext(
                UUID.randomUUID(), UUID.randomUUID(), WorkspaceChannel.BACKGROUND);
    }

    private static void assertSystemScope() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        assertThat(context.isSystem()).isTrue();
        assertThat(context.isAuthentication()).isFalse();
        assertThat(context.actorId()).isEqualTo(WorkspaceContext.NIL_ID);
        assertThat(context.workspaceId()).isEqualTo(WorkspaceContext.NIL_ID);
    }
}
