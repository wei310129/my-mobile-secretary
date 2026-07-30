package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarExternalSyncActualEntryPathTest extends IntegrationTestBase {

    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intents;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void naturalRequestUsesTypedZeroMutationExplanationEntryPath() {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seedAccount(actorId, workspaceId);
        WorkspaceContext context = new WorkspaceContext(
                actorId,
                workspaceId,
                WorkspaceChannel.TEST,
                "test",
                "calendar-external-sync-entry");

        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            long started = System.nanoTime();
            IntentResult result = handle(
                    "直接同步到我的 iPhone 行事曆",
                    command("直接同步到我的 iPhone 行事曆"));
            Duration latency =
                    Duration.ofNanos(System.nanoTime() - started);
            IntentResult replay = handle(
                    "直接同步到我的 iPhone 行事曆",
                    command("直接同步到我的 iPhone 行事曆"));

            assertThat(result.action())
                    .isEqualTo(
                            IntentResult.Action
                                    .CALENDAR_EXTERNAL_SYNC_EXPLAINED);
            assertThat(result.message())
                    .contains("ICS", "iOS", "尚未")
                    .doesNotContain("已同步", "已寫入");
            assertThat(replay.action()).isEqualTo(result.action());
            assertThat(latency).isLessThan(Duration.ofMillis(1500));
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM calendar_plan",
                            Long.class))
                    .isZero();
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM task", Long.class))
                    .isZero();
        }
    }

    private IntentResult handle(String text, IntentCommand command) {
        interpreter.nextCommand(command);
        return RequestCorrelationContext.run(
                UUID.randomUUID(), () -> intents.handle(text, "TEST"));
    }

    private static IntentCommand command(String sourceText) {
        return new IntentCommand(
                IntentCommand.Type.EXPLAIN_CALENDAR_EXTERNAL_SYNC,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                sourceText);
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, 'Calendar sync user', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id,
                    created_at, updated_at)
                VALUES (?, 'Calendar sync workspace', 'PERSONAL', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                actorId);
    }
}
