package com.aproject.internal.aidispatcher.coordination;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DispatcherDrainService {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DispatcherDrainService(JdbcTemplate jdbcTemplate,
                                  PlatformTransactionManager transactionManager,
                                  Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public DispatcherDrainStatus requestDrain(String actorId) {
        return transactionTemplate.execute(status -> {
            Lane lane = lockLane();
            Instant now = Instant.now(clock);
            if (lane.activeRunId() != null) {
                jdbcTemplate.update("""
                        UPDATE dispatcher_lane SET coordinator_drain_requested = TRUE,
                            version = version + 1, updated_at = ?
                        WHERE lane_key = 'CODEX_DEVELOPMENT'
                        """, Timestamp.from(now));
                audit("DRAIN_REQUESTED", actorId, now);
                return new DispatcherDrainStatus("DRAINING", lane.activeRunId());
            }
            jdbcTemplate.update("""
                    UPDATE dispatcher_lane SET state = 'PAUSED', coordinator_drain_requested = TRUE,
                        last_error_code = 'COORDINATOR_DRAIN', paused_reason = 'Coordinator requested a safe drain',
                        version = version + 1, updated_at = ? WHERE lane_key = 'CODEX_DEVELOPMENT'
                    """, Timestamp.from(now));
            audit("DRAINED", actorId, now);
            return new DispatcherDrainStatus("DRAINED", null);
        });
    }

    public DispatcherDrainStatus resume(String actorId) {
        return transactionTemplate.execute(status -> {
            Lane lane = lockLane();
            if (lane.activeRunId() != null) {
                throw new IllegalStateException("Cannot resume Dispatcher while a run outcome may still be active");
            }
            if (!"COORDINATOR_DRAIN".equals(lane.lastErrorCode())) {
                throw new IllegalStateException("Only a coordinator drain pause may be resumed");
            }
            Instant now = Instant.now(clock);
            Long pending = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = 'PENDING'", Long.class);
            String state = pending != null && pending > 0 ? "WAITING" : "IDLE";
            jdbcTemplate.update("""
                    UPDATE dispatcher_lane SET state = ?, coordinator_drain_requested = FALSE,
                        last_error_code = NULL, paused_reason = NULL, version = version + 1, updated_at = ?
                    WHERE lane_key = 'CODEX_DEVELOPMENT' AND active_run_id IS NULL
                    """, state, Timestamp.from(now));
            audit("RESUMED", actorId, now);
            return new DispatcherDrainStatus("RESUMED", null);
        });
    }

    private Lane lockLane() {
        return jdbcTemplate.queryForObject("""
                SELECT active_run_id, last_error_code FROM dispatcher_lane
                WHERE lane_key = 'CODEX_DEVELOPMENT' FOR UPDATE
                """, (rs, row) -> new Lane(rs.getObject("active_run_id", UUID.class), rs.getString("last_error_code")));
    }

    private void audit(String action, String actorId, Instant occurredAt) {
        jdbcTemplate.update("INSERT INTO dispatcher_drain_audit (audit_id, action, actor_id, occurred_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), action, actorId, Timestamp.from(occurredAt));
    }

    public record DispatcherDrainStatus(String state, UUID activeRunId) { }
    private record Lane(UUID activeRunId, String lastErrorCode) { }
}
