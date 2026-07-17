package com.aproject.internal.aidispatcher.codex;

import com.aproject.internal.aidispatcher.config.CodexLifecycleProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CodexLifecycleService {

    private static final Set<String> ACTIVE_RUN_STATUSES =
            Set.of("STARTING", "RUNNING", "OUTCOME_UNKNOWN");

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final CodexLifecycleProperties properties;

    public CodexLifecycleService(JdbcTemplate jdbcTemplate,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock,
                                 CodexLifecycleProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
    }

    public CodexLifecycleResult onCodexFinish(UUID runId,
                                              long fencingToken,
                                              CodexCompletion completion) {
        if (runId == null || fencingToken <= 0 || completion == null) {
            throw new IllegalArgumentException("runId, positive fencingToken and completion are required");
        }
        CodexLifecycleResult result = transactionTemplate.execute(
                status -> finishLocked(runId, fencingToken, completion));
        if (result == null) {
            throw new IllegalStateException("Codex finish transaction returned no result");
        }
        return result;
    }

    public CodexLifecycleResult heartbeat(UUID runId, long fencingToken) {
        if (runId == null || fencingToken <= 0) {
            throw new IllegalArgumentException("runId and a positive fencingToken are required");
        }
        CodexLifecycleResult result = transactionTemplate.execute(
                status -> heartbeatLocked(runId, fencingToken));
        if (result == null) {
            throw new IllegalStateException("Codex heartbeat transaction returned no result");
        }
        return result;
    }

    public CodexLifecycleResult onCodexOutcomeUnknown(UUID runId,
                                                      long fencingToken,
                                                      String errorCode,
                                                      Instant observedAt) {
        if (runId == null || fencingToken <= 0 || observedAt == null) {
            throw new IllegalArgumentException(
                    "runId, positive fencingToken and observedAt are required");
        }
        String boundedErrorCode = requireErrorCode(errorCode);
        CodexLifecycleResult result = transactionTemplate.execute(status ->
                retainUnknownOutcomeLocked(
                        runId, fencingToken, boundedErrorCode, observedAt));
        if (result == null) {
            throw new IllegalStateException("Unknown outcome transaction returned no result");
        }
        return result;
    }

    private CodexLifecycleResult finishLocked(UUID runId,
                                              long fencingToken,
                                              CodexCompletion completion) {
        LaneRow lane = lockLane();
        List<RunRow> runs = jdbcTemplate.query("""
                SELECT status, fencing_token, event_count
                FROM dispatcher_run
                WHERE run_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new RunRow(
                        resultSet.getString("status"),
                        resultSet.getLong("fencing_token"),
                        resultSet.getInt("event_count")), runId);
        if (runs.isEmpty()) {
            return CodexLifecycleResult.of(CodexLifecycleResult.Outcome.NOT_FOUND, 0);
        }
        RunRow run = runs.getFirst();
        if (!ACTIVE_RUN_STATUSES.contains(run.status())) {
            return CodexLifecycleResult.of(
                    CodexLifecycleResult.Outcome.DUPLICATE, pendingEventCount());
        }
        if (!runId.equals(lane.activeRunId())
                || fencingToken != lane.fencingToken()
                || fencingToken != run.fencingToken()) {
            return CodexLifecycleResult.of(
                    CodexLifecycleResult.Outcome.STALE, pendingEventCount());
        }

        if (completion.status() == CodexCompletion.Status.SUCCEEDED) {
            consumeClaimedEvents(runId, run.eventCount(), completion.completedAt());
        } else {
            releaseClaimedEvents(runId, run.eventCount());
        }
        markRunTerminal(runId, completion);

        PendingAggregate pending = pendingAggregate();
        moveLaneAfterFinish(completion, pending);
        return CodexLifecycleResult.of(
                CodexLifecycleResult.Outcome.COMPLETED, pending.eventCount());
    }

    private CodexLifecycleResult heartbeatLocked(UUID runId, long fencingToken) {
        LaneRow lane = lockLane();
        if (!runId.equals(lane.activeRunId())
                || fencingToken != lane.fencingToken()
                || !"RUNNING".equals(lane.state())) {
            return CodexLifecycleResult.of(
                    CodexLifecycleResult.Outcome.HEARTBEAT_REJECTED, pendingEventCount());
        }
        Instant now = Instant.now(clock);
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_run
                SET last_heartbeat_at = ?, heartbeat_deadline = ?, updated_at = ?
                WHERE run_id = ? AND fencing_token = ? AND status = 'RUNNING'
                """, timestamp(now), timestamp(now.plus(properties.heartbeatTimeout())),
                timestamp(now), runId, fencingToken);
        if (updated != 1) {
            return CodexLifecycleResult.of(
                    CodexLifecycleResult.Outcome.HEARTBEAT_REJECTED, pendingEventCount());
        }
        return CodexLifecycleResult.of(
                CodexLifecycleResult.Outcome.HEARTBEAT_ACCEPTED, pendingEventCount());
    }

    private CodexLifecycleResult retainUnknownOutcomeLocked(UUID runId,
                                                             long fencingToken,
                                                             String errorCode,
                                                             Instant observedAt) {
        LaneRow lane = lockLane();
        List<RunRow> runs = jdbcTemplate.query("""
                SELECT status, fencing_token, event_count
                FROM dispatcher_run
                WHERE run_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new RunRow(
                        resultSet.getString("status"),
                        resultSet.getLong("fencing_token"),
                        resultSet.getInt("event_count")), runId);
        if (runs.isEmpty()) {
            return CodexLifecycleResult.of(CodexLifecycleResult.Outcome.NOT_FOUND, 0);
        }
        RunRow run = runs.getFirst();
        if (!ACTIVE_RUN_STATUSES.contains(run.status())) {
            return CodexLifecycleResult.of(
                    CodexLifecycleResult.Outcome.DUPLICATE, pendingEventCount());
        }
        if (!runId.equals(lane.activeRunId())
                || fencingToken != lane.fencingToken()
                || fencingToken != run.fencingToken()) {
            return CodexLifecycleResult.of(
                    CodexLifecycleResult.Outcome.STALE, pendingEventCount());
        }
        if ("OUTCOME_UNKNOWN".equals(run.status()) && "PAUSED".equals(lane.state())) {
            return CodexLifecycleResult.of(
                    CodexLifecycleResult.Outcome.OUTCOME_RETAINED, pendingEventCount());
        }

        int runUpdated = jdbcTemplate.update("""
                UPDATE dispatcher_run
                SET status = 'OUTCOME_UNKNOWN', last_error_code = ?,
                    heartbeat_deadline = NULL, updated_at = ?
                WHERE run_id = ? AND fencing_token = ?
                  AND status IN ('STARTING', 'RUNNING', 'OUTCOME_UNKNOWN')
                """, errorCode, timestamp(observedAt), runId, fencingToken);
        int laneUpdated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'PAUSED', last_error_code = ?,
                    paused_reason = 'Codex execution may have repository side effects; operator verification required',
                    version = version + 1, updated_at = ?
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                  AND active_run_id = ? AND fencing_token = ?
                  AND state IN ('STARTING', 'RUNNING', 'RECOVERING', 'PAUSED')
                """, errorCode, timestamp(observedAt), runId, fencingToken);
        if (runUpdated != 1 || laneUpdated != 1) {
            throw new IllegalStateException("Could not retain uncertain Codex outcome atomically");
        }
        return CodexLifecycleResult.of(
                CodexLifecycleResult.Outcome.OUTCOME_RETAINED, pendingEventCount());
    }

    private LaneRow lockLane() {
        return jdbcTemplate.queryForObject("""
                SELECT state, active_run_id, fencing_token
                FROM dispatcher_lane
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                FOR UPDATE
                """, (resultSet, rowNumber) -> new LaneRow(
                        resultSet.getString("state"),
                        resultSet.getObject("active_run_id", UUID.class),
                        resultSet.getLong("fencing_token")));
    }

    private void consumeClaimedEvents(UUID runId, int expectedCount, Instant completedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_event
                SET processing_state = 'CONSUMED', active_run_id = NULL,
                    consumed_by_run_id = ?, consumed_at = ?
                WHERE active_run_id = ? AND processing_state = 'CLAIMED'
                """, runId, timestamp(completedAt), runId);
        requireEventSettlementCount(updated, expectedCount);
    }

    private void releaseClaimedEvents(UUID runId, int expectedCount) {
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_event
                SET processing_state = 'PENDING', active_run_id = NULL
                WHERE active_run_id = ? AND processing_state = 'CLAIMED'
                """, runId);
        requireEventSettlementCount(updated, expectedCount);
    }

    private static void requireEventSettlementCount(int actual, int expected) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Run event settlement mismatch: expected " + expected + " but updated " + actual);
        }
    }

    private void markRunTerminal(UUID runId, CodexCompletion completion) {
        String terminalStatus = completion.status() == CodexCompletion.Status.SUCCEEDED
                ? "SUCCEEDED" : "FAILED";
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_run
                SET status = ?, finished_at = ?, result_code = ?,
                    heartbeat_deadline = NULL, updated_at = ?
                WHERE run_id = ? AND status IN ('STARTING', 'RUNNING', 'OUTCOME_UNKNOWN')
                """, terminalStatus, timestamp(completion.completedAt()), completion.resultCode(),
                timestamp(Instant.now(clock)), runId);
        if (updated != 1) {
            throw new IllegalStateException("Active run could not become terminal");
        }
    }

    private PendingAggregate pendingAggregate() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS event_count,
                       MIN(recorded_at) AS first_recorded_at,
                       MAX(recorded_at) AS last_recorded_at
                FROM dispatcher_event
                WHERE processing_state = 'PENDING'
                """, (resultSet, rowNumber) -> new PendingAggregate(
                        resultSet.getLong("event_count"),
                        instant(resultSet.getTimestamp("first_recorded_at")),
                        instant(resultSet.getTimestamp("last_recorded_at"))));
    }

    private long pendingEventCount() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = 'PENDING'
                """, Long.class);
        return count == null ? 0 : count;
    }

    private void moveLaneAfterFinish(CodexCompletion completion, PendingAggregate pending) {
        Instant now = Instant.now(clock);
        boolean hasPending = pending.eventCount() > 0;
        String nextState = hasPending ? "WAITING" : "IDLE";
        Instant retryNotBefore = completion.status() == CodexCompletion.Status.FAILED
                ? completion.completedAt().plus(properties.retryDelay()) : null;
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = ?, active_run_id = NULL,
                    observed_first_pending_at = ?, observed_last_pending_at = ?,
                    eligible_at = NULL, retry_not_before = ?,
                    consecutive_failure_count = CASE WHEN ? = 'FAILED'
                        THEN consecutive_failure_count + 1 ELSE 0 END,
                    last_error_code = CASE WHEN ? = 'FAILED' THEN ? ELSE NULL END,
                    paused_reason = NULL, version = version + 1, updated_at = ?
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, nextState, timestamp(pending.firstRecordedAt()),
                timestamp(pending.lastRecordedAt()), timestamp(retryNotBefore),
                completion.status().name(), completion.status().name(), completion.resultCode(),
                timestamp(now));
        if (updated != 1) {
            throw new IllegalStateException("Dispatcher lane could not finish active run");
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String requireErrorCode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("errorCode is required");
        }
        String result = value.strip();
        if (result.length() > 100) {
            throw new IllegalArgumentException("errorCode must not exceed 100 characters");
        }
        return result;
    }

    private record LaneRow(String state, UUID activeRunId, long fencingToken) {
    }

    private record RunRow(String status, long fencingToken, int eventCount) {
    }

    private record PendingAggregate(long eventCount, Instant firstRecordedAt, Instant lastRecordedAt) {
    }
}
