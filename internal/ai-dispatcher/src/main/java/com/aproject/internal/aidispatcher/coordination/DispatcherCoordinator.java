package com.aproject.internal.aidispatcher.coordination;

import com.aproject.internal.aidispatcher.config.DispatcherProperties;
import com.aproject.internal.aidispatcher.domain.DispatchSchedule;
import com.aproject.internal.aidispatcher.domain.DispatcherState;
import com.aproject.internal.aidispatcher.domain.DispatcherStateMachine;
import com.aproject.internal.aidispatcher.domain.PendingEventWindow;
import com.aproject.internal.aidispatcher.domain.QuietPeriodPolicy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DispatcherCoordinator {

    static final String LANE_KEY = "CODEX_DEVELOPMENT";
    static final String SESSION_KEY = "development-main";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final DispatcherInstanceIdentity instanceIdentity;
    private final QuietPeriodPolicy quietPeriodPolicy;
    private final DispatcherStateMachine stateMachine = new DispatcherStateMachine();

    public DispatcherCoordinator(JdbcTemplate jdbcTemplate,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock,
                                 DispatcherProperties properties,
                                 DispatcherInstanceIdentity instanceIdentity) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.instanceIdentity = instanceIdentity;
        this.quietPeriodPolicy = new QuietPeriodPolicy(
                properties.quietPeriod(), properties.maximumWait());
    }

    public DispatcherTickResult tick() {
        DispatcherTickResult result = transactionTemplate.execute(status -> tickLocked());
        if (result == null) {
            throw new IllegalStateException("Dispatcher coordination transaction returned no result");
        }
        return result;
    }

    private DispatcherTickResult tickLocked() {
        Instant now = Instant.now(clock);
        LaneRow lane = lockLane();
        if (lane.state() == DispatcherState.PAUSED) {
            return DispatcherTickResult.paused();
        }
        if (lane.state() == DispatcherState.STARTING
                || lane.state() == DispatcherState.RUNNING
                || lane.state() == DispatcherState.RECOVERING) {
            return DispatcherTickResult.busy(lane.state());
        }

        PendingAggregate aggregate = pendingAggregate();
        if (aggregate.eventCount() == 0) {
            moveToIdle(lane.state(), now);
            return DispatcherTickResult.idle();
        }

        Instant previousRunFinishedAt = lastRunFinishedAt();
        DispatchSchedule schedule = schedule(aggregate, previousRunFinishedAt, lane.retryNotBefore());
        if (!schedule.isEligibleAt(now)) {
            moveToWaiting(lane.state(), aggregate, schedule, now);
            return DispatcherTickResult.waiting(aggregate.eventCount(), schedule.eligibleAt());
        }

        List<PendingEventRow> events = lockPendingEvents();
        if (events.isEmpty()) {
            moveToIdle(lane.state(), now);
            return DispatcherTickResult.idle();
        }

        PendingAggregate lockedAggregate = PendingAggregate.from(events);
        DispatchSchedule lockedSchedule = schedule(
                lockedAggregate, previousRunFinishedAt, lane.retryNotBefore());
        if (!lockedSchedule.isEligibleAt(now)) {
            moveToWaiting(lane.state(), lockedAggregate, lockedSchedule, now);
            return DispatcherTickResult.waiting(
                    lockedAggregate.eventCount(), lockedSchedule.eligibleAt());
        }
        if (!sessionIsReady()) {
            moveToPausedForSession(lane.state(), now);
            return DispatcherTickResult.paused();
        }
        return claimRun(lane, events, now);
    }

    private LaneRow lockLane() {
        List<LaneRow> rows = jdbcTemplate.query("""
                SELECT state, fencing_token, retry_not_before
                FROM dispatcher_lane
                WHERE lane_key = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new LaneRow(
                        DispatcherState.valueOf(resultSet.getString("state")),
                        resultSet.getLong("fencing_token"),
                        instant(resultSet.getTimestamp("retry_not_before"))),
                LANE_KEY);
        if (rows.size() != 1) {
            throw new IllegalStateException("Dispatcher lane is missing or duplicated");
        }
        return rows.getFirst();
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

    private List<PendingEventRow> lockPendingEvents() {
        return jdbcTemplate.query("""
                SELECT id, recorded_at
                FROM dispatcher_event
                WHERE processing_state = 'PENDING'
                ORDER BY recorded_at, id
                FOR UPDATE
                """, (resultSet, rowNumber) -> new PendingEventRow(
                        resultSet.getLong("id"),
                        resultSet.getTimestamp("recorded_at").toInstant()));
    }

    private Instant lastRunFinishedAt() {
        Timestamp value = jdbcTemplate.queryForObject("""
                SELECT MAX(finished_at)
                FROM dispatcher_run
                WHERE lane_key = ?
                """, Timestamp.class, LANE_KEY);
        return instant(value);
    }

    private boolean sessionIsReady() {
        Boolean ready = jdbcTemplate.queryForObject("""
                SELECT status = 'READY' AND external_session_id IS NOT NULL
                FROM agent_session
                WHERE session_key = ?
                """, Boolean.class, SESSION_KEY);
        return Boolean.TRUE.equals(ready);
    }

    private DispatchSchedule schedule(PendingAggregate aggregate,
                                      Instant previousRunFinishedAt,
                                      Instant retryNotBefore) {
        return quietPeriodPolicy.schedule(new PendingEventWindow(
                aggregate.firstRecordedAt(),
                aggregate.lastRecordedAt(),
                aggregate.eventCount()), previousRunFinishedAt, retryNotBefore);
    }

    private void moveToIdle(DispatcherState current, Instant now) {
        if (current != DispatcherState.IDLE) {
            stateMachine.transition(current, DispatcherState.IDLE);
        }
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'IDLE', active_run_id = NULL,
                    observed_first_pending_at = NULL,
                    observed_last_pending_at = NULL,
                    eligible_at = NULL, retry_not_before = NULL,
                    version = version + 1, updated_at = ?
                WHERE lane_key = ?
                """, timestamp(now), LANE_KEY);
        requireSingleLaneUpdate(updated);
    }

    private void moveToWaiting(DispatcherState current,
                               PendingAggregate aggregate,
                               DispatchSchedule schedule,
                               Instant now) {
        if (current != DispatcherState.WAITING) {
            stateMachine.transition(current, DispatcherState.WAITING);
        }
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'WAITING', active_run_id = NULL,
                    observed_first_pending_at = ?, observed_last_pending_at = ?,
                    eligible_at = ?, version = version + 1, updated_at = ?
                WHERE lane_key = ?
                """, timestamp(aggregate.firstRecordedAt()),
                timestamp(aggregate.lastRecordedAt()),
                timestamp(schedule.eligibleAt()), timestamp(now), LANE_KEY);
        requireSingleLaneUpdate(updated);
    }

    private void moveToPausedForSession(DispatcherState current, Instant now) {
        stateMachine.transition(current, DispatcherState.PAUSED);
        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'PAUSED', active_run_id = NULL,
                    eligible_at = NULL, retry_not_before = NULL,
                    last_error_code = 'SESSION_NOT_READY',
                    paused_reason = 'The configured Codex session is not bound and ready',
                    version = version + 1, updated_at = ?
                WHERE lane_key = ?
                """, timestamp(now), LANE_KEY);
        requireSingleLaneUpdate(updated);
    }

    private DispatcherTickResult claimRun(LaneRow lane,
                                          List<PendingEventRow> events,
                                          Instant now) {
        DispatcherState waitingState = lane.state();
        if (waitingState == DispatcherState.IDLE) {
            waitingState = stateMachine.transition(waitingState, DispatcherState.WAITING);
        }
        stateMachine.transition(waitingState, DispatcherState.STARTING);

        long nextToken = Math.addExact(lane.fencingToken(), 1);
        UUID runId = UUID.randomUUID();
        int eventCount = events.size();
        jdbcTemplate.update("""
                INSERT INTO dispatcher_run (
                    run_id, lane_key, run_sequence, fencing_token, status, session_key,
                    starter_instance_id, event_count, start_requested_at,
                    recovery_attempt_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'STARTING', ?, ?, ?, ?, 0, ?, ?)
                """, runId, LANE_KEY, nextToken, nextToken, SESSION_KEY,
                instanceIdentity.value(), eventCount, timestamp(now), timestamp(now), timestamp(now));

        int position = 0;
        for (PendingEventRow event : events) {
            position++;
            int claimed = jdbcTemplate.update("""
                    UPDATE dispatcher_event
                    SET processing_state = 'CLAIMED', active_run_id = ?
                    WHERE id = ? AND processing_state = 'PENDING'
                    """, runId, event.id());
            if (claimed != 1) {
                throw new IllegalStateException("Pending event changed while locked: " + event.id());
            }
            jdbcTemplate.update("""
                    INSERT INTO dispatcher_run_event (run_id, event_id, position, created_at)
                    VALUES (?, ?, ?, ?)
                    """, runId, event.id(), position, timestamp(now));
        }

        int updated = jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'STARTING', active_run_id = ?, fencing_token = ?,
                    observed_first_pending_at = NULL,
                    observed_last_pending_at = NULL,
                    eligible_at = NULL, retry_not_before = NULL,
                    last_error_code = NULL,
                    version = version + 1, updated_at = ?
                WHERE lane_key = ?
                """, runId, nextToken, timestamp(now), LANE_KEY);
        requireSingleLaneUpdate(updated);
        return DispatcherTickResult.claimed(runId, eventCount);
    }

    private static void requireSingleLaneUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Dispatcher lane update affected " + updated + " rows");
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record LaneRow(DispatcherState state, long fencingToken, Instant retryNotBefore) {
    }

    private record PendingEventRow(long id, Instant recordedAt) {
    }

    private record PendingAggregate(long eventCount, Instant firstRecordedAt, Instant lastRecordedAt) {

        private static PendingAggregate from(List<PendingEventRow> rows) {
            return new PendingAggregate(
                    rows.size(), rows.getFirst().recordedAt(), rows.getLast().recordedAt());
        }
    }
}
