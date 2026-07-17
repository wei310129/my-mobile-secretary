package com.aproject.internal.aidispatcher.codex.cli;

import com.aproject.internal.aidispatcher.codex.CodexStartCommand;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

final class CodexExecutionAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    CodexExecutionAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void recordStarted(String executionId,
                       CodexStartCommand command,
                       long processId,
                       Instant startedAt) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO codex_execution_attempt (
                    external_execution_id, run_id, fencing_token, adapter_type,
                    process_id, status, started_at, last_progress_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'CLI', ?, 'RUNNING', ?, ?, ?, ?)
                """, executionId, command.runId(), command.fencingToken(), processId,
                timestamp(startedAt), timestamp(startedAt),
                timestamp(startedAt), timestamp(startedAt));
        if (inserted != 1) {
            throw new IllegalStateException("Could not record Codex CLI execution start");
        }
    }

    void recordProgress(String executionId, Instant progressAt) {
        jdbcTemplate.update("""
                UPDATE codex_execution_attempt
                SET last_progress_at = GREATEST(last_progress_at, ?), updated_at = ?
                WHERE external_execution_id = ? AND status = 'RUNNING'
                """, timestamp(progressAt), timestamp(progressAt), executionId);
    }

    void recordTerminal(String executionId,
                        String status,
                        String terminalEvent,
                        int exitCode,
                        CodexCliProgress progress,
                        String diagnosticCode,
                        String stderrExcerpt,
                        Instant finishedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE codex_execution_attempt
                SET status = ?, finished_at = ?, terminal_event = ?, cli_exit_code = ?,
                    input_tokens = ?, cached_input_tokens = ?, output_tokens = ?,
                    reasoning_output_tokens = ?, diagnostic_code = ?, stderr_excerpt = ?,
                    last_progress_at = GREATEST(last_progress_at, ?), updated_at = ?
                WHERE external_execution_id = ? AND status = 'RUNNING'
                """, status, timestamp(finishedAt), terminalEvent, exitCode,
                progress.inputTokens(), progress.cachedInputTokens(),
                progress.outputTokens(), progress.reasoningOutputTokens(),
                diagnosticCode, stderrExcerpt, timestamp(finishedAt), timestamp(finishedAt),
                executionId);
        if (updated != 1) {
            throw new IllegalStateException("Could not record Codex CLI execution terminal state");
        }
    }

    CodexExecutionAuditSnapshot find(String executionId) {
        List<CodexExecutionAuditSnapshot> rows = jdbcTemplate.query("""
                SELECT external_execution_id, run_id, fencing_token, status,
                       started_at, last_progress_at, finished_at,
                       terminal_event, cli_exit_code, diagnostic_code
                FROM codex_execution_attempt
                WHERE external_execution_id = ?
                """, (resultSet, rowNumber) -> new CodexExecutionAuditSnapshot(
                        resultSet.getString("external_execution_id"),
                        resultSet.getObject("run_id", java.util.UUID.class),
                        resultSet.getLong("fencing_token"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("started_at").toInstant(),
                        resultSet.getTimestamp("last_progress_at").toInstant(),
                        instant(resultSet.getTimestamp("finished_at")),
                        resultSet.getString("terminal_event"),
                        resultSet.getObject("cli_exit_code", Integer.class),
                        resultSet.getString("diagnostic_code")), executionId);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
