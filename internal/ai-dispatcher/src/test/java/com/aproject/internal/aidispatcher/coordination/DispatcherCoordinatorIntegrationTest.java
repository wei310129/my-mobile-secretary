package com.aproject.internal.aidispatcher.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.internal.aidispatcher.config.DispatcherProperties;
import com.aproject.internal.aidispatcher.session.AgentSessionRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
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
class DispatcherCoordinatorIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-07-17T00:00:00Z");
    private static final String SOURCE = "main-conversation-feed-v1";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareDatabase() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'IDLE', active_run_id = NULL, fencing_token = 0,
                    observed_first_pending_at = NULL, observed_last_pending_at = NULL,
                    eligible_at = NULL, retry_not_before = NULL,
                    consecutive_failure_count = 0, last_error_code = NULL,
                    paused_reason = NULL, version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """);
        jdbcTemplate.update("DELETE FROM dispatcher_run_event");
        jdbcTemplate.update("DELETE FROM dispatcher_event");
        jdbcTemplate.update("DELETE FROM dispatcher_run");
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = 'READY', external_session_id = 'session-test',
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
    }

    @Test
    void waitsUntilTheQuietPeriodExpires() {
        insertEvent("event-1", BASE);

        DispatcherTickResult result = coordinatorAt(BASE.plus(Duration.ofMinutes(4)), "instance-a")
                .tick();

        assertThat(result.outcome()).isEqualTo(DispatcherTickResult.Outcome.WAITING);
        assertThat(result.eligibleAt()).isEqualTo(BASE.plus(Duration.ofMinutes(5)));
        assertThat(laneState()).isEqualTo("WAITING");
        assertThat(runCount()).isZero();
    }

    @Test
    void atomicallyClaimsOneHundredEventsIntoOneRun() {
        for (int index = 1; index <= 100; index++) {
            insertEvent("event-" + index, BASE);
        }

        DispatcherTickResult result = coordinatorAt(BASE.plus(Duration.ofMinutes(5)), "instance-a")
                .tick();

        assertThat(result.outcome()).isEqualTo(DispatcherTickResult.Outcome.CLAIMED);
        assertThat(result.eventCount()).isEqualTo(100);
        assertThat(runCount()).isEqualTo(1);
        assertThat(countByEventState("CLAIMED")).isEqualTo(100);
        assertThat(runEventCount(result.runId())).isEqualTo(100);
        assertThat(laneState()).isEqualTo("STARTING");
    }

    @Test
    void twoCoordinatorsCannotCreateTwoRuns() throws Exception {
        insertEvent("event-1", BASE);
        DispatcherCoordinator first = coordinatorAt(BASE.plus(Duration.ofMinutes(5)), "instance-a");
        DispatcherCoordinator second = coordinatorAt(BASE.plus(Duration.ofMinutes(5)), "instance-b");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<DispatcherTickResult> firstResult = executor.submit(() -> {
                start.await();
                return first.tick();
            });
            Future<DispatcherTickResult> secondResult = executor.submit(() -> {
                start.await();
                return second.tick();
            });
            start.countDown();

            assertThat(List.of(firstResult.get().outcome(), secondResult.get().outcome()))
                    .containsExactlyInAnyOrder(
                            DispatcherTickResult.Outcome.CLAIMED,
                            DispatcherTickResult.Outcome.BUSY);
            assertThat(runCount()).isEqualTo(1);
            assertThat(countByEventState("CLAIMED")).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void eventsArrivingAfterAClaimRemainPendingForTheNextRun() {
        insertEvent("event-1", BASE);
        DispatcherCoordinator coordinator =
                coordinatorAt(BASE.plus(Duration.ofMinutes(5)), "instance-a");
        DispatcherTickResult first = coordinator.tick();

        insertEvent("event-2", BASE.plus(Duration.ofMinutes(5)));
        DispatcherTickResult whileStarting = coordinator.tick();

        assertThat(first.outcome()).isEqualTo(DispatcherTickResult.Outcome.CLAIMED);
        assertThat(whileStarting.outcome()).isEqualTo(DispatcherTickResult.Outcome.BUSY);
        assertThat(countByEventState("CLAIMED")).isEqualTo(1);
        assertThat(countByEventState("PENDING")).isEqualTo(1);
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void pausesBeforeClaimingWhenTheSessionIsNotReady() {
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = 'UNBOUND', external_session_id = NULL,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
        insertEvent("event-1", BASE);

        DispatcherTickResult result = coordinatorAt(BASE.plus(Duration.ofMinutes(5)), "instance-a")
                .tick();

        assertThat(result.outcome()).isEqualTo(DispatcherTickResult.Outcome.PAUSED);
        assertThat(laneState()).isEqualTo("PAUSED");
        assertThat(runCount()).isZero();
        assertThat(countByEventState("PENDING")).isEqualTo(1);
    }

    @Test
    void bindingTheSessionResumesASessionReadinessPause() {
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = 'UNBOUND', external_session_id = NULL,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
        insertEvent("event-1", BASE);
        coordinatorAt(BASE.plus(Duration.ofMinutes(5)), "instance-a").tick();
        AgentSessionRegistry registry = new AgentSessionRegistry(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(BASE.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));

        AgentSessionRegistry.SessionBinding binding =
                registry.bindDevelopmentSession("bound-session-id");

        assertThat(binding.externalSessionId()).isEqualTo("bound-session-id");
        assertThat(laneState()).isEqualTo("WAITING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM agent_session WHERE session_key = 'development-main'
                """, String.class)).isEqualTo("READY");
    }

    private DispatcherCoordinator coordinatorAt(Instant now, String instanceId) {
        return new DispatcherCoordinator(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(now, ZoneOffset.UTC),
                new DispatcherProperties(
                        false,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(30)),
                new DispatcherInstanceIdentity(instanceId));
    }

    private void insertEvent(String eventId, Instant recordedAt) {
        jdbcTemplate.update("""
                INSERT INTO dispatcher_event (
                    source_key, source_event_id, trigger_type, subject_ref,
                    schema_version, occurred_at, recorded_at, processing_state, metadata)
                VALUES (?, ?, 'line.conversation.recorded', ?, 1, ?, ?, 'PENDING', '{}'::jsonb)
                """, SOURCE, eventId, "line-message:" + eventId,
                Timestamp.from(recordedAt), Timestamp.from(recordedAt));
    }

    private String laneState() {
        return jdbcTemplate.queryForObject("""
                SELECT state FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, String.class);
    }

    private Long runCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dispatcher_run", Long.class);
    }

    private Long countByEventState(String state) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = ?
                """, Long.class, state);
    }

    private Long runEventCount(java.util.UUID runId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dispatcher_run_event WHERE run_id = ?
                """, Long.class, runId);
    }
}
