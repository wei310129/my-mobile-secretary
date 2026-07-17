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
                       COUNT(e.id) FILTER (WHERE e.processing_state = 'PENDING') AS pending_count,
                       COUNT(e.id) FILTER (WHERE e.processing_state = 'CLAIMED') AS claimed_count
                FROM dispatcher_lane l
                LEFT JOIN dispatcher_event e ON TRUE
                WHERE l.lane_key = 'CODEX_DEVELOPMENT'
                GROUP BY l.lane_key, l.state, l.consecutive_failure_count,
                         l.last_error_code, l.paused_reason, l.updated_at
                """, (resultSet, rowNumber) -> new Snapshot(
                        resultSet.getString("state"),
                        resultSet.getInt("consecutive_failure_count"),
                        resultSet.getString("last_error_code"),
                        resultSet.getString("paused_reason"),
                        resultSet.getTimestamp("updated_at"),
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
        if (snapshot.lastErrorCode() != null) {
            details.put("lastErrorCode", snapshot.lastErrorCode());
        }
        if (snapshot.pausedReason() != null) {
            details.put("pausedReason", snapshot.pausedReason());
        }

        Health.Builder builder = switch (snapshot.state()) {
            case "PAUSED" -> Health.status("DEGRADED");
            case "RECOVERING" -> Health.status("UNKNOWN");
            default -> Health.up();
        };
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
            long pendingCount,
            long claimedCount
    ) {
    }
}
