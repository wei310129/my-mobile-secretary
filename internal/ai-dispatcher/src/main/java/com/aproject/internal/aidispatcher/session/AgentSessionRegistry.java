package com.aproject.internal.aidispatcher.session;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentSessionRegistry {

    public static final String DEVELOPMENT_SESSION_KEY = "development-main";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public AgentSessionRegistry(JdbcTemplate jdbcTemplate,
                                PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public SessionBinding bindDevelopmentSession(String externalSessionId) {
        String safeId = requireText(externalSessionId, "externalSessionId", 200);
        SessionBinding result = transactionTemplate.execute(status -> bindLocked(safeId));
        if (result == null) {
            throw new IllegalStateException("Session binding transaction returned no result");
        }
        return result;
    }

    private SessionBinding bindLocked(String externalSessionId) {
        String laneState = jdbcTemplate.queryForObject("""
                SELECT state
                FROM dispatcher_lane
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                FOR UPDATE
                """, String.class);
        if (List.of("STARTING", "RUNNING", "RECOVERING").contains(laneState)) {
            throw new IllegalStateException("Cannot rebind the session while a run may be active");
        }

        Instant now = Instant.now(clock);
        int updated = jdbcTemplate.update("""
                UPDATE agent_session
                SET external_session_id = ?, status = 'READY', last_verified_at = ?,
                    version = version + 1, updated_at = ?
                WHERE session_key = ?
                """, externalSessionId, Timestamp.from(now), Timestamp.from(now),
                DEVELOPMENT_SESSION_KEY);
        if (updated != 1) {
            throw new IllegalStateException("Development session registry row is missing");
        }

        if ("PAUSED".equals(laneState)) {
            Long pending = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = 'PENDING'
                    """, Long.class);
            String resumedState = pending != null && pending > 0 ? "WAITING" : "IDLE";
            jdbcTemplate.update("""
                    UPDATE dispatcher_lane
                    SET state = ?, paused_reason = NULL, last_error_code = NULL,
                        version = version + 1, updated_at = ?
                    WHERE lane_key = 'CODEX_DEVELOPMENT'
                      AND state = 'PAUSED'
                      AND active_run_id IS NULL
                    """, resumedState, Timestamp.from(now));
        }
        return new SessionBinding(
                DEVELOPMENT_SESSION_KEY, "開發主要對話", externalSessionId, now);
    }

    private static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds " + maximumLength + " characters");
        }
        return stripped;
    }

    public record SessionBinding(
            String sessionKey,
            String displayName,
            String externalSessionId,
            Instant verifiedAt
    ) {
    }
}
