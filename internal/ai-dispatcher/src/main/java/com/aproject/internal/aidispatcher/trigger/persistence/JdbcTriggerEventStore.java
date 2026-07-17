package com.aproject.internal.aidispatcher.trigger.persistence;

import com.aproject.internal.aidispatcher.trigger.domain.DevelopmentTriggerEvent;
import com.aproject.internal.aidispatcher.trigger.domain.TriggerEventPage;
import com.aproject.internal.aidispatcher.trigger.domain.TriggerIngestionResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcTriggerEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public JdbcTriggerEventStore(JdbcTemplate jdbcTemplate,
                                 PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public TriggerIngestionResult ingest(TriggerEventPage page) {
        Objects.requireNonNull(page, "page");
        TriggerIngestionResult result = transactionTemplate.execute(status -> ingestLocked(page));
        if (result == null) {
            throw new IllegalStateException("Trigger ingestion transaction returned no result");
        }
        return result;
    }

    public String currentCursor(String sourceKey) {
        List<String> cursors = jdbcTemplate.query("""
                SELECT cursor_value FROM trigger_cursor WHERE source_key = ?
                """, (resultSet, rowNumber) -> resultSet.getString("cursor_value"), sourceKey);
        if (cursors.isEmpty()) {
            throw new IllegalArgumentException("Unknown trigger source: " + sourceKey);
        }
        return cursors.getFirst();
    }

    private TriggerIngestionResult ingestLocked(TriggerEventPage page) {
        List<String> cursors = jdbcTemplate.query("""
                SELECT cursor_value
                FROM trigger_cursor
                WHERE source_key = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getString("cursor_value"), page.sourceKey());
        if (cursors.isEmpty()) {
            throw new IllegalArgumentException("Unknown trigger source: " + page.sourceKey());
        }

        String storedCursor = cursors.getFirst();
        if (!Objects.equals(storedCursor, page.expectedCursor())) {
            return TriggerIngestionResult.stale(page.events().size(), storedCursor);
        }

        Instant recordedAt = Instant.now(clock);
        int inserted = 0;
        for (DevelopmentTriggerEvent event : page.events()) {
            inserted += insertEvent(page.sourceKey(), event, recordedAt);
        }
        int updated = jdbcTemplate.update("""
                UPDATE trigger_cursor
                SET cursor_value = ?, last_polled_at = ?, version = version + 1, updated_at = ?
                WHERE source_key = ?
                """, page.nextCursor(), timestamp(recordedAt), timestamp(recordedAt), page.sourceKey());
        if (updated != 1) {
            throw new IllegalStateException("Trigger cursor disappeared while locked");
        }
        return TriggerIngestionResult.applied(page.events().size(), inserted, page.nextCursor());
    }

    private int insertEvent(String sourceKey, DevelopmentTriggerEvent event, Instant recordedAt) {
        return jdbcTemplate.update("""
                INSERT INTO dispatcher_event (
                    source_key, source_event_id, trigger_type, subject_ref,
                    schema_version, occurred_at, recorded_at, processing_state, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', CAST(? AS JSONB))
                ON CONFLICT (source_key, source_event_id) DO NOTHING
                """, sourceKey, event.sourceEventId(), event.triggerType(), event.subjectRef(),
                event.schemaVersion(), timestamp(event.occurredAt()), timestamp(recordedAt),
                event.metadataJson());
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
