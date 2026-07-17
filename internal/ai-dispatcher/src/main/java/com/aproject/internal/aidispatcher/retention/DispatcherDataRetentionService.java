package com.aproject.internal.aidispatcher.retention;

import com.aproject.internal.aidispatcher.config.DispatcherRetentionProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DispatcherDataRetentionService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final DispatcherRetentionProperties properties;

    public DispatcherDataRetentionService(JdbcTemplate jdbcTemplate,
                                          Clock clock,
                                          DispatcherRetentionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    public int purgeExpiredConsumedPayloads() {
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(properties.consumedPayloadRetention());
        return jdbcTemplate.update("""
                UPDATE dispatcher_event
                SET metadata = '{}'::jsonb, payload_purged_at = ?
                WHERE processing_state = 'CONSUMED'
                  AND consumed_at < ?
                  AND payload_purged_at IS NULL
                """, Timestamp.from(now), Timestamp.from(cutoff));
    }
}
