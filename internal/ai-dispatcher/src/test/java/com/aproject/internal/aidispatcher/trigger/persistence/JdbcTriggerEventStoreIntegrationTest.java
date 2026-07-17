package com.aproject.internal.aidispatcher.trigger.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.internal.aidispatcher.trigger.domain.DevelopmentTriggerEvent;
import com.aproject.internal.aidispatcher.trigger.domain.TriggerEventPage;
import com.aproject.internal.aidispatcher.trigger.domain.TriggerIngestionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class JdbcTriggerEventStoreIntegrationTest {

    private static final String SOURCE = "main-conversation-feed-v1";
    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private JdbcTriggerEventStore store;

    @BeforeEach
    void prepareDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("DELETE FROM dispatcher_run_event");
        jdbcTemplate.update("DELETE FROM dispatcher_event");
        jdbcTemplate.update("""
                UPDATE trigger_cursor
                SET cursor_value = NULL, last_polled_at = NULL, version = 0,
                    updated_at = CURRENT_TIMESTAMP
                WHERE source_key = ?
                """, SOURCE);
        store = new JdbcTriggerEventStore(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persistsEventsBeforeAdvancingTheCursor() {
        TriggerIngestionResult result = store.ingest(new TriggerEventPage(
                SOURCE, null, "cursor-1", List.of(event("event-1"), event("event-2"))));

        assertThat(result.status()).isEqualTo(TriggerIngestionResult.Status.APPLIED);
        assertThat(result.receivedCount()).isEqualTo(2);
        assertThat(result.insertedCount()).isEqualTo(2);
        assertThat(eventCount()).isEqualTo(2);
        assertThat(cursor()).isEqualTo("cursor-1");
    }

    @Test
    void deduplicatesAReplayedSourceEvent() {
        store.ingest(new TriggerEventPage(
                SOURCE, null, "cursor-1", List.of(event("event-1"))));

        TriggerIngestionResult replay = store.ingest(new TriggerEventPage(
                SOURCE, "cursor-1", "cursor-2", List.of(event("event-1"))));

        assertThat(replay.insertedCount()).isZero();
        assertThat(eventCount()).isEqualTo(1);
        assertThat(cursor()).isEqualTo("cursor-2");
    }

    @Test
    void rollsBackEventsAndCursorWhenAnyEventIsInvalid() {
        DevelopmentTriggerEvent invalidJson = new DevelopmentTriggerEvent(
                "event-2", "line.conversation.recorded", "line-message:2", 1, NOW, "not-json");

        assertThatThrownBy(() -> store.ingest(new TriggerEventPage(
                SOURCE, null, "cursor-1", List.of(event("event-1"), invalidJson))))
                .isInstanceOf(RuntimeException.class);

        assertThat(eventCount()).isZero();
        assertThat(cursor()).isNull();
    }

    @Test
    void rejectsAStaleCursorWithoutWritingEvents() {
        store.ingest(new TriggerEventPage(SOURCE, null, "cursor-1", List.of()));

        TriggerIngestionResult stale = store.ingest(new TriggerEventPage(
                SOURCE, null, "cursor-2", List.of(event("event-1"))));

        assertThat(stale.status()).isEqualTo(TriggerIngestionResult.Status.STALE_CURSOR);
        assertThat(stale.currentCursor()).isEqualTo("cursor-1");
        assertThat(eventCount()).isZero();
        assertThat(cursor()).isEqualTo("cursor-1");
    }

    @Test
    void concurrentPollersWithTheSameCursorCannotBothApply() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<TriggerIngestionResult> first = executor.submit(() -> {
                start.await();
                return store.ingest(new TriggerEventPage(
                        SOURCE, null, "cursor-a", List.of(event("event-a"))));
            });
            Future<TriggerIngestionResult> second = executor.submit(() -> {
                start.await();
                return store.ingest(new TriggerEventPage(
                        SOURCE, null, "cursor-b", List.of(event("event-b"))));
            });
            start.countDown();

            assertThat(List.of(first.get().status(), second.get().status()))
                    .containsExactlyInAnyOrder(
                            TriggerIngestionResult.Status.APPLIED,
                            TriggerIngestionResult.Status.STALE_CURSOR);
            assertThat(eventCount()).isEqualTo(1);
            assertThat(cursor()).isIn("cursor-a", "cursor-b");
        } finally {
            executor.shutdownNow();
        }
    }

    private static DevelopmentTriggerEvent event(String id) {
        return new DevelopmentTriggerEvent(
                id,
                "line.conversation.recorded",
                "line-message:" + id,
                1,
                NOW,
                "{\"messageType\":\"TEXT\"}");
    }

    private Long eventCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dispatcher_event", Long.class);
    }

    private String cursor() {
        return jdbcTemplate.queryForObject(
                "SELECT cursor_value FROM trigger_cursor WHERE source_key = ?",
                String.class,
                SOURCE);
    }
}
