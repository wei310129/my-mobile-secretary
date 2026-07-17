package com.aproject.internal.aidispatcher.codex;

import com.aproject.internal.aidispatcher.coordination.DispatcherInstanceIdentity;
import com.aproject.internal.aidispatcher.config.CodexLifecycleProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnBean(CodexExecutionPort.class)
public class CodexLaunchService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final DispatcherInstanceIdentity instanceIdentity;
    private final CodexExecutionPort executionPort;
    private final CodexLifecycleProperties lifecycleProperties;

    public CodexLaunchService(JdbcTemplate jdbcTemplate,
                              PlatformTransactionManager transactionManager,
                              Clock clock,
                              DispatcherInstanceIdentity instanceIdentity,
                              CodexExecutionPort executionPort,
                              CodexLifecycleProperties lifecycleProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.instanceIdentity = instanceIdentity;
        this.executionPort = executionPort;
        this.lifecycleProperties = lifecycleProperties;
    }

    public CodexLaunchResult launch(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("runId is required");
        }
        LaunchPreparation preparation = transactionTemplate.execute(
                status -> claimLaunchHandoff(runId));
        if (preparation == null) {
            throw new IllegalStateException("Launch handoff transaction returned no result");
        }
        if (preparation.command() == null) {
            return CodexLaunchResult.of(preparation.outcome(), runId);
        }

        CodexStartReceipt receipt = executionPort.startCodex(preparation.command());
        Boolean acknowledged = transactionTemplate.execute(
                status -> acknowledgeStarted(preparation.command(), receipt));
        if (!Boolean.TRUE.equals(acknowledged)) {
            return CodexLaunchResult.of(CodexLaunchResult.Outcome.STALE_ACK, runId);
        }
        return CodexLaunchResult.started(runId, receipt.externalExecutionId());
    }

    private LaunchPreparation claimLaunchHandoff(UUID runId) {
        List<LaunchRow> rows = jdbcTemplate.query("""
                SELECT r.fencing_token, r.launch_dispatched_at,
                       s.session_key, s.display_name, s.external_session_id, s.status
                FROM dispatcher_lane l
                JOIN dispatcher_run r ON r.run_id = l.active_run_id
                JOIN agent_session s ON s.session_key = r.session_key
                WHERE l.lane_key = 'CODEX_DEVELOPMENT'
                  AND l.state = 'STARTING'
                  AND r.status = 'STARTING'
                  AND r.run_id = ?
                FOR UPDATE OF l, r, s
                """, (resultSet, rowNumber) -> new LaunchRow(
                        resultSet.getLong("fencing_token"),
                        resultSet.getTimestamp("launch_dispatched_at"),
                        resultSet.getString("session_key"),
                        resultSet.getString("display_name"),
                        resultSet.getString("external_session_id"),
                        resultSet.getString("status")), runId);
        if (rows.isEmpty()) {
            return LaunchPreparation.withoutCommand(CodexLaunchResult.Outcome.NOT_ACTIVE);
        }
        LaunchRow row = rows.getFirst();
        if (row.launchDispatchedAt() != null) {
            return LaunchPreparation.withoutCommand(CodexLaunchResult.Outcome.ALREADY_DISPATCHED);
        }
        if (!"READY".equals(row.sessionStatus()) || row.externalSessionId() == null) {
            throw new IllegalStateException("Codex session became unavailable before launch");
        }

        List<CodexEventReference> events = jdbcTemplate.query("""
                SELECT e.source_key, e.source_event_id, e.trigger_type, e.subject_ref,
                       e.schema_version, e.occurred_at
                FROM dispatcher_run_event re
                JOIN dispatcher_event e ON e.id = re.event_id
                WHERE re.run_id = ?
                ORDER BY re.position
                """, (resultSet, rowNumber) -> new CodexEventReference(
                        resultSet.getString("source_key"),
                        resultSet.getString("source_event_id"),
                        resultSet.getString("trigger_type"),
                        resultSet.getString("subject_ref"),
                        resultSet.getInt("schema_version"),
                        resultSet.getTimestamp("occurred_at").toInstant()), runId);

        Instant dispatchedAt = Instant.now(clock);
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_run
                SET launch_dispatched_at = ?, launch_owner_id = ?, updated_at = ?
                WHERE run_id = ? AND status = 'STARTING' AND launch_dispatched_at IS NULL
                """, Timestamp.from(dispatchedAt), instanceIdentity.value(),
                Timestamp.from(dispatchedAt), runId);
        if (updated != 1) {
            throw new IllegalStateException("Codex launch handoff was concurrently claimed");
        }
        return LaunchPreparation.withCommand(new CodexStartCommand(
                runId,
                row.fencingToken(),
                row.sessionKey(),
                row.displayName(),
                row.externalSessionId(),
                events));
    }

    private boolean acknowledgeStarted(CodexStartCommand command, CodexStartReceipt receipt) {
        List<String> states = jdbcTemplate.query("""
                SELECT l.state
                FROM dispatcher_lane l
                JOIN dispatcher_run r ON r.run_id = l.active_run_id
                WHERE l.lane_key = 'CODEX_DEVELOPMENT'
                  AND l.active_run_id = ?
                  AND l.fencing_token = ?
                  AND r.status = 'STARTING'
                FOR UPDATE OF l, r
                """, (resultSet, rowNumber) -> resultSet.getString("state"),
                command.runId(), command.fencingToken());
        if (states.size() != 1 || !"STARTING".equals(states.getFirst())) {
            return false;
        }

        Instant acknowledgedAt = Instant.now(clock);
        int runUpdated = jdbcTemplate.update("""
                UPDATE dispatcher_run
                SET status = 'RUNNING', external_execution_id = ?, started_at = ?,
                    last_heartbeat_at = ?, heartbeat_deadline = ?, updated_at = ?
                WHERE run_id = ? AND fencing_token = ? AND status = 'STARTING'
                """, receipt.externalExecutionId(), Timestamp.from(receipt.acceptedAt()),
                Timestamp.from(receipt.acceptedAt()),
                Timestamp.from(receipt.acceptedAt().plus(lifecycleProperties.heartbeatTimeout())),
                Timestamp.from(acknowledgedAt),
                command.runId(), command.fencingToken());
        int laneUpdated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'RUNNING', version = version + 1, updated_at = ?
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                  AND active_run_id = ? AND fencing_token = ? AND state = 'STARTING'
                """, Timestamp.from(acknowledgedAt), command.runId(), command.fencingToken());
        if (runUpdated != 1 || laneUpdated != 1) {
            throw new IllegalStateException("Could not acknowledge Codex start atomically");
        }
        return true;
    }

    private record LaunchRow(
            long fencingToken,
            Timestamp launchDispatchedAt,
            String sessionKey,
            String displayName,
            String externalSessionId,
            String sessionStatus
    ) {
    }

    private record LaunchPreparation(
            CodexLaunchResult.Outcome outcome,
            CodexStartCommand command
    ) {
        private static LaunchPreparation withCommand(CodexStartCommand command) {
            return new LaunchPreparation(null, command);
        }

        private static LaunchPreparation withoutCommand(CodexLaunchResult.Outcome outcome) {
            return new LaunchPreparation(outcome, null);
        }
    }
}
