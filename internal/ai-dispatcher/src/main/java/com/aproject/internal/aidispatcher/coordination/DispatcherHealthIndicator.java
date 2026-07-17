package com.aproject.internal.aidispatcher.coordination;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("aiDispatcher")
public class DispatcherHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public DispatcherHealthIndicator(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Snapshot snapshot = jdbcTemplate.queryForObject("""
                SELECT l.state, l.consecutive_failure_count, l.last_error_code,
                       l.paused_reason, l.updated_at,
                       s.status AS session_status, s.provider AS session_provider,
                       s.version AS session_binding_version,
                       s.last_verified_at AS session_last_verified_at,
                       COUNT(e.id) FILTER (WHERE e.processing_state = 'PENDING') AS pending_count,
                       COUNT(e.id) FILTER (WHERE e.processing_state = 'CLAIMED') AS claimed_count
                FROM dispatcher_lane l
                LEFT JOIN agent_session s ON s.session_key = 'development-main'
                LEFT JOIN dispatcher_event e ON TRUE
                WHERE l.lane_key = 'CODEX_DEVELOPMENT'
                GROUP BY l.lane_key, l.state, l.consecutive_failure_count,
                         l.last_error_code, l.paused_reason, l.updated_at,
                         s.status, s.provider, s.version, s.last_verified_at
                """, (resultSet, rowNumber) -> new Snapshot(
                        resultSet.getString("state"),
                        resultSet.getInt("consecutive_failure_count"),
                        resultSet.getString("last_error_code"),
                        resultSet.getString("paused_reason"),
                        resultSet.getTimestamp("updated_at"),
                        resultSet.getString("session_status"),
                        resultSet.getString("session_provider"),
                        resultSet.getObject("session_binding_version", Long.class),
                        resultSet.getTimestamp("session_last_verified_at"),
                        resultSet.getLong("pending_count"),
                        resultSet.getLong("claimed_count")));
        if (snapshot == null) {
            return Health.down().withDetail("reason", "dispatcher lane missing").build();
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", snapshot.state());
        details.put("pendingEvents", snapshot.pendingCount());
        details.put("claimedEvents", snapshot.claimedCount());
        details.put("consecutiveFailures", snapshot.consecutiveFailureCount());
        details.put("stateAgeSeconds", stateAgeSeconds(snapshot.updatedAt()));
        details.put("sessionStatus",
                snapshot.sessionStatus() == null ? "MISSING" : snapshot.sessionStatus());
        if (snapshot.sessionProvider() != null) {
            details.put("sessionProvider", snapshot.sessionProvider());
        }
        if (snapshot.sessionBindingVersion() != null) {
            details.put("sessionBindingVersion", snapshot.sessionBindingVersion());
        }
        if (snapshot.sessionLastVerifiedAt() != null) {
            details.put("sessionVerificationAgeSeconds",
                    stateAgeSeconds(snapshot.sessionLastVerifiedAt()));
        }
        if (snapshot.lastErrorCode() != null) {
            details.put("lastErrorCode", snapshot.lastErrorCode());
        }
        if (snapshot.pausedReason() != null) {
            details.put("pausedReason", snapshot.pausedReason());
        }

        Health.Builder builder;
        if ("PAUSED".equals(snapshot.state())) {
            builder = Health.status("DEGRADED");
        } else if ("RECOVERING".equals(snapshot.state())) {
            builder = Health.status("UNKNOWN");
        } else if (!"READY".equals(snapshot.sessionStatus())) {
            builder = Health.status("DEGRADED");
        } else {
            builder = Health.up();
        }
        return builder.withDetails(details).build();
    }

    private long stateAgeSeconds(Timestamp updatedAt) {
        if (updatedAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(updatedAt.toInstant(), Instant.now(clock)).toSeconds());
    }

    private record Snapshot(
            String state,
            int consecutiveFailureCount,
            String lastErrorCode,
            String pausedReason,
            Timestamp updatedAt,
            String sessionStatus,
            String sessionProvider,
            Long sessionBindingVersion,
            Timestamp sessionLastVerifiedAt,
            long pendingCount,
            long claimedCount
    ) {
    }
}
