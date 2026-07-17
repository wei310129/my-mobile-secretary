package com.aproject.internal.aidispatcher.codex;

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
public class CodexRecoveryService {

    private static final String LANE_KEY = "CODEX_DEVELOPMENT";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final CodexExecutionPort executionPort;
    private final CodexLifecycleService lifecycleService;
    private final CodexLifecycleProperties properties;

    public CodexRecoveryService(JdbcTemplate jdbcTemplate,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                CodexExecutionPort executionPort,
                                CodexLifecycleService lifecycleService,
                                CodexLifecycleProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.executionPort = executionPort;
        this.lifecycleService = lifecycleService;
        this.properties = properties;
    }

    public CodexRecoveryResult recover() {
        RecoveryPreparation preparation = transactionTemplate.execute(status -> prepareRecovery());
        if (preparation == null) {
            throw new IllegalStateException("Recovery preparation returned no result");
        }
        if (preparation.query() == null) {
            return preparation.result();
        }

        CodexExecutionObservation observation;
        try {
            observation = executionPort.queryExecution(preparation.query());
            if (observation == null) {
                observation = CodexExecutionObservation.unknown(
                        "NULL_EXECUTION_OBSERVATION", Instant.now(clock));
            }
        } catch (RuntimeException queryFailure) {
            observation = CodexExecutionObservation.unknown(
                    "EXECUTION_QUERY_FAILED", Instant.now(clock));
        }
        return reconcile(preparation, observation);
    }

    private RecoveryPreparation prepareRecovery() {
        LaneRow lane = lockLane();
        if (lane.activeRunId() == null) {
            return RecoveryPreparation.withoutQuery(CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.NO_ACTIVE_RUN, null, 0));
        }
        List<RunRow> runs = jdbcTemplate.query("""
                SELECT r.status, r.fencing_token, r.external_execution_id,
                       r.launch_dispatched_at, r.heartbeat_deadline,
                       r.recovery_attempt_count, r.updated_at, s.external_session_id
                FROM dispatcher_run r
                JOIN agent_session s ON s.session_key = r.session_key
                WHERE r.run_id = ?
                FOR UPDATE OF r, s
                """, (resultSet, rowNumber) -> new RunRow(
                        resultSet.getString("status"),
                        resultSet.getLong("fencing_token"),
                        resultSet.getString("external_execution_id"),
                        instant(resultSet.getTimestamp("launch_dispatched_at")),
                        instant(resultSet.getTimestamp("heartbeat_deadline")),
                        resultSet.getInt("recovery_attempt_count"),
                        resultSet.getTimestamp("updated_at").toInstant(),
                        resultSet.getString("external_session_id")), lane.activeRunId());
        if (runs.size() != 1) {
            throw new IllegalStateException("Active dispatcher run is missing");
        }
        RunRow run = runs.getFirst();
        if (run.fencingToken() != lane.fencingToken()) {
            throw new IllegalStateException("Lane and active run fencing tokens differ");
        }
        if ("PAUSED".equals(lane.state())) {
            return RecoveryPreparation.withoutQuery(CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.PAUSED,
                    lane.activeRunId(), run.recoveryAttemptCount()));
        }

        Instant now = Instant.now(clock);
        if ("STARTING".equals(run.status()) && run.launchDispatchedAt() == null) {
            return RecoveryPreparation.withoutQuery(CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.LAUNCH_REQUIRED,
                    lane.activeRunId(), run.recoveryAttemptCount()));
        }
        if ("STARTING".equals(run.status())
                && run.launchDispatchedAt().plus(properties.heartbeatTimeout()).isAfter(now)) {
            return RecoveryPreparation.withoutQuery(CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.HEALTHY,
                    lane.activeRunId(), run.recoveryAttemptCount()));
        }
        if ("RUNNING".equals(run.status())
                && run.heartbeatDeadline() != null
                && run.heartbeatDeadline().isAfter(now)) {
            return RecoveryPreparation.withoutQuery(CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.HEALTHY,
                    lane.activeRunId(), run.recoveryAttemptCount()));
        }
        if ("OUTCOME_UNKNOWN".equals(run.status())
                && run.updatedAt().plus(properties.retryDelay()).isAfter(now)) {
            return RecoveryPreparation.withoutQuery(CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.RETRY_PENDING,
                    lane.activeRunId(), run.recoveryAttemptCount()));
        }
        if (!("STARTING".equals(run.status())
                || "RUNNING".equals(run.status())
                || "OUTCOME_UNKNOWN".equals(run.status()))) {
            return RecoveryPreparation.withoutQuery(CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.STALE,
                    lane.activeRunId(), run.recoveryAttemptCount()));
        }

        int attempt = run.recoveryAttemptCount() + 1;
        int runUpdated = jdbcTemplate.update("""
                UPDATE dispatcher_run
                SET status = 'OUTCOME_UNKNOWN', recovery_attempt_count = ?,
                    last_error_code = 'RECOVERY_REQUIRED', updated_at = ?
                WHERE run_id = ? AND fencing_token = ?
                  AND status IN ('STARTING', 'RUNNING', 'OUTCOME_UNKNOWN')
                """, attempt, timestamp(now), lane.activeRunId(), lane.fencingToken());
        int laneUpdated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'RECOVERING', last_error_code = 'RECOVERY_REQUIRED',
                    version = version + 1, updated_at = ?
                WHERE lane_key = ? AND active_run_id = ? AND fencing_token = ?
                  AND state IN ('STARTING', 'RUNNING', 'RECOVERING')
                """, timestamp(now), LANE_KEY, lane.activeRunId(), lane.fencingToken());
        if (runUpdated != 1 || laneUpdated != 1) {
            throw new IllegalStateException("Could not enter recovery atomically");
        }
        CodexExecutionQuery query = new CodexExecutionQuery(
                lane.activeRunId(), lane.fencingToken(), run.externalExecutionId(),
                run.externalSessionId(), now);
        return RecoveryPreparation.withQuery(
                CodexRecoveryResult.of(
                        CodexRecoveryResult.Outcome.RETRY_PENDING, lane.activeRunId(), attempt),
                query);
    }

    private CodexRecoveryResult reconcile(RecoveryPreparation preparation,
                                          CodexExecutionObservation observation) {
        return switch (observation.status()) {
            case RUNNING -> restoreRunning(preparation, observation);
            case SUCCEEDED -> settleTerminal(
                    preparation, observation, CodexCompletion.Status.SUCCEEDED);
            case FAILED -> settleTerminal(
                    preparation, observation, CodexCompletion.Status.FAILED);
            case NOT_FOUND -> settleTerminal(
                    preparation,
                    CodexExecutionObservation.failed(
                            observation.externalExecutionId(),
                            observation.resultCode() == null
                                    ? "EXECUTION_NOT_FOUND" : observation.resultCode(),
                            observation.observedAt()),
                    CodexCompletion.Status.FAILED);
            case UNKNOWN -> retainUnknown(preparation, observation);
        };
    }

    private CodexRecoveryResult restoreRunning(RecoveryPreparation preparation,
                                               CodexExecutionObservation observation) {
        CodexRecoveryResult result = transactionTemplate.execute(status -> {
            ActiveRecovery active = lockMatchingRecovery(preparation);
            if (active == null) {
                return CodexRecoveryResult.of(
                        CodexRecoveryResult.Outcome.STALE,
                        preparation.query().runId(), preparation.result().recoveryAttemptCount());
            }
            Instant observedAt = observation.observedAt();
            int runUpdated = jdbcTemplate.update("""
                    UPDATE dispatcher_run
                    SET status = 'RUNNING', external_execution_id = COALESCE(?, external_execution_id),
                        last_heartbeat_at = ?, heartbeat_deadline = ?,
                        last_error_code = NULL, updated_at = ?
                    WHERE run_id = ? AND fencing_token = ? AND status = 'OUTCOME_UNKNOWN'
                    """, observation.externalExecutionId(), timestamp(observedAt),
                    timestamp(observedAt.plus(properties.heartbeatTimeout())), timestamp(observedAt),
                    preparation.query().runId(), preparation.query().fencingToken());
            int laneUpdated = jdbcTemplate.update("""
                    UPDATE dispatcher_lane
                    SET state = 'RUNNING', last_error_code = NULL,
                        paused_reason = NULL, version = version + 1, updated_at = ?
                    WHERE lane_key = ? AND active_run_id = ? AND fencing_token = ?
                      AND state = 'RECOVERING'
                    """, timestamp(observedAt), LANE_KEY,
                    preparation.query().runId(), preparation.query().fencingToken());
            if (runUpdated != 1 || laneUpdated != 1) {
                throw new IllegalStateException("Could not restore observed running execution");
            }
            return CodexRecoveryResult.of(
                    CodexRecoveryResult.Outcome.RECOVERED_RUNNING,
                    preparation.query().runId(), active.attemptCount());
        });
        if (result == null) {
            throw new IllegalStateException("Running recovery reconciliation returned no result");
        }
        return result;
    }

    private CodexRecoveryResult settleTerminal(RecoveryPreparation preparation,
                                               CodexExecutionObservation observation,
                                               CodexCompletion.Status status) {
        CodexLifecycleResult settled = lifecycleService.onCodexFinish(
                preparation.query().runId(),
                preparation.query().fencingToken(),
                new CodexCompletion(status, observation.resultCode(), observation.completedAt()));
        CodexRecoveryResult.Outcome outcome = settled.outcome() == CodexLifecycleResult.Outcome.COMPLETED
                ? CodexRecoveryResult.Outcome.COMPLETED : CodexRecoveryResult.Outcome.STALE;
        return CodexRecoveryResult.of(
                outcome, preparation.query().runId(), preparation.result().recoveryAttemptCount());
    }

    private CodexRecoveryResult retainUnknown(RecoveryPreparation preparation,
                                              CodexExecutionObservation observation) {
        CodexRecoveryResult result = transactionTemplate.execute(status -> {
            ActiveRecovery active = lockMatchingRecovery(preparation);
            if (active == null) {
                return CodexRecoveryResult.of(
                        CodexRecoveryResult.Outcome.STALE,
                        preparation.query().runId(), preparation.result().recoveryAttemptCount());
            }
            boolean exhausted = active.attemptCount() >= properties.maxRecoveryAttempts();
            String laneState = exhausted ? "PAUSED" : "RECOVERING";
            String reason = exhausted ? "CODEX_OUTCOME_UNCONFIRMED" : null;
            String errorCode = observation.resultCode() == null
                    ? "CODEX_OUTCOME_UNKNOWN" : observation.resultCode();
            Instant now = Instant.now(clock);
            jdbcTemplate.update("""
                    UPDATE dispatcher_run
                    SET last_error_code = ?, updated_at = ?
                    WHERE run_id = ? AND fencing_token = ? AND status = 'OUTCOME_UNKNOWN'
                    """, errorCode, timestamp(now),
                    preparation.query().runId(), preparation.query().fencingToken());
            int updated = jdbcTemplate.update("""
                    UPDATE dispatcher_lane
                    SET state = ?, last_error_code = ?, paused_reason = ?,
                        version = version + 1, updated_at = ?
                    WHERE lane_key = ? AND active_run_id = ? AND fencing_token = ?
                      AND state = 'RECOVERING'
                    """, laneState, errorCode, reason, timestamp(now), LANE_KEY,
                    preparation.query().runId(), preparation.query().fencingToken());
            if (updated != 1) {
                throw new IllegalStateException("Could not retain uncertain Codex outcome");
            }
            return CodexRecoveryResult.of(
                    exhausted ? CodexRecoveryResult.Outcome.PAUSED
                            : CodexRecoveryResult.Outcome.RETRY_PENDING,
                    preparation.query().runId(), active.attemptCount());
        });
        if (result == null) {
            throw new IllegalStateException("Unknown recovery reconciliation returned no result");
        }
        return result;
    }

    private ActiveRecovery lockMatchingRecovery(RecoveryPreparation preparation) {
        LaneRow lane = lockLane();
        if (!preparation.query().runId().equals(lane.activeRunId())
                || preparation.query().fencingToken() != lane.fencingToken()
                || !"RECOVERING".equals(lane.state())) {
            return null;
        }
        List<Integer> attempts = jdbcTemplate.query("""
                SELECT recovery_attempt_count
                FROM dispatcher_run
                WHERE run_id = ? AND fencing_token = ? AND status = 'OUTCOME_UNKNOWN'
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getInt(1),
                preparation.query().runId(), preparation.query().fencingToken());
        return attempts.size() == 1 ? new ActiveRecovery(attempts.getFirst()) : null;
    }

    private LaneRow lockLane() {
        return jdbcTemplate.queryForObject("""
                SELECT state, active_run_id, fencing_token
                FROM dispatcher_lane
                WHERE lane_key = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new LaneRow(
                        resultSet.getString("state"),
                        resultSet.getObject("active_run_id", UUID.class),
                        resultSet.getLong("fencing_token")), LANE_KEY);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record LaneRow(String state, UUID activeRunId, long fencingToken) {
    }

    private record RunRow(
            String status,
            long fencingToken,
            String externalExecutionId,
            Instant launchDispatchedAt,
            Instant heartbeatDeadline,
            int recoveryAttemptCount,
            Instant updatedAt,
            String externalSessionId
    ) {
    }

    private record ActiveRecovery(int attemptCount) {
    }

    private record RecoveryPreparation(
            CodexRecoveryResult result,
            CodexExecutionQuery query
    ) {
        private static RecoveryPreparation withoutQuery(CodexRecoveryResult result) {
            return new RecoveryPreparation(result, null);
        }

        private static RecoveryPreparation withQuery(
                CodexRecoveryResult result, CodexExecutionQuery query) {
            return new RecoveryPreparation(result, query);
        }
    }
}
