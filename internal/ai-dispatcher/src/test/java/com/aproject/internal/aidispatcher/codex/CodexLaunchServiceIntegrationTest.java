package com.aproject.internal.aidispatcher.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.internal.aidispatcher.config.DispatcherProperties;
import com.aproject.internal.aidispatcher.config.CodexLifecycleProperties;
import com.aproject.internal.aidispatcher.coordination.DispatcherCoordinator;
import com.aproject.internal.aidispatcher.coordination.DispatcherInstanceIdentity;
import com.aproject.internal.aidispatcher.coordination.DispatcherTickResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
class CodexLaunchServiceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-07-17T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private Clock clock;

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
                SET status = 'READY', external_session_id = 'codex-session-123',
                    bound_at = CURRENT_TIMESTAMP, last_verified_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
        clock = Clock.fixed(BASE.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);
    }

    @Test
    void startsOutsideTheClaimTransactionAndAcknowledgesRunning() {
        UUID runId = claimOneEvent();
        AtomicReference<CodexStartCommand> captured = new AtomicReference<>();
        CodexExecutionPort port = command -> {
            captured.set(command);
            return new CodexStartReceipt("execution-1", BASE.plus(Duration.ofMinutes(5)));
        };

        CodexLaunchResult result = launchService(port).launch(runId);

        assertThat(result.outcome()).isEqualTo(CodexLaunchResult.Outcome.STARTED);
        assertThat(runStatus(runId)).isEqualTo("RUNNING");
        assertThat(laneState()).isEqualTo("RUNNING");
        assertThat(captured.get().sessionDisplayName()).isEqualTo("開發主要對話");
        assertThat(captured.get().externalSessionId()).isEqualTo("codex-session-123");
        assertThat(captured.get().events()).hasSize(1);
        assertThat(captured.get().events().getFirst().metadataJson()).isEqualTo("{}");
    }

    @Test
    void concurrentLaunchersCallTheExternalPortOnlyOnce() throws Exception {
        UUID runId = claimOneEvent();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch externalCallEntered = new CountDownLatch(1);
        CountDownLatch releaseExternalCall = new CountDownLatch(1);
        CodexExecutionPort port = command -> {
            calls.incrementAndGet();
            externalCallEntered.countDown();
            await(releaseExternalCall);
            return new CodexStartReceipt("execution-1", BASE.plus(Duration.ofMinutes(5)));
        };
        CodexLaunchService first = launchService(port);
        CodexLaunchService second = launchService(port);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<CodexLaunchResult> firstResult = executor.submit(() -> first.launch(runId));
            assertThat(externalCallEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<CodexLaunchResult> secondResult = executor.submit(() -> second.launch(runId));
            CodexLaunchResult duplicate = secondResult.get(5, TimeUnit.SECONDS);
            releaseExternalCall.countDown();

            assertThat(firstResult.get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(CodexLaunchResult.Outcome.STARTED);
            assertThat(duplicate.outcome())
                    .isEqualTo(CodexLaunchResult.Outcome.ALREADY_DISPATCHED);
            assertThat(calls).hasValue(1);
        } finally {
            releaseExternalCall.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void externalCallDoesNotHoldTheDispatcherLaneLock() throws Exception {
        UUID runId = claimOneEvent();
        CountDownLatch externalCallEntered = new CountDownLatch(1);
        CountDownLatch releaseExternalCall = new CountDownLatch(1);
        CodexExecutionPort port = command -> {
            externalCallEntered.countDown();
            await(releaseExternalCall);
            return new CodexStartReceipt("execution-1", BASE.plus(Duration.ofMinutes(5)));
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<CodexLaunchResult> launch = executor.submit(() -> launchService(port).launch(runId));
            assertThat(externalCallEntered.await(5, TimeUnit.SECONDS)).isTrue();

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("""
                             SELECT state FROM dispatcher_lane
                             WHERE lane_key = 'CODEX_DEVELOPMENT'
                             FOR UPDATE NOWAIT
                             """)) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("STARTING");
                }
                connection.commit();
            }
            releaseExternalCall.countDown();
            assertThat(launch.get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(CodexLaunchResult.Outcome.STARTED);
        } finally {
            releaseExternalCall.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void uncertainExternalFailureRemainsDurablyStartingForRecovery() {
        UUID runId = claimOneEvent();
        CodexExecutionPort port = command -> {
            throw new IllegalStateException("connection lost after request");
        };

        assertThatThrownBy(() -> launchService(port).launch(runId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connection lost");

        assertThat(runStatus(runId)).isEqualTo("STARTING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT launch_dispatched_at IS NOT NULL
                FROM dispatcher_run WHERE run_id = ?
                """, Boolean.class, runId)).isTrue();
        assertThat(laneState()).isEqualTo("STARTING");
    }

    private UUID claimOneEvent() {
        jdbcTemplate.update("""
                INSERT INTO dispatcher_event (
                    source_key, source_event_id, trigger_type, subject_ref,
                    schema_version, occurred_at, recorded_at, processing_state, metadata)
                VALUES ('main-conversation-feed-v1', 'event-1',
                        'line.conversation.recorded', 'line-message:1',
                        1, ?, ?, 'PENDING', '{}'::jsonb)
                """, Timestamp.from(BASE), Timestamp.from(BASE));
        DispatcherCoordinator coordinator = new DispatcherCoordinator(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                clock,
                new DispatcherProperties(
                        false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                new DispatcherInstanceIdentity());
        DispatcherTickResult tick = coordinator.tick();
        assertThat(tick.outcome()).isEqualTo(DispatcherTickResult.Outcome.CLAIMED);
        return tick.runId();
    }

    private CodexLaunchService launchService(CodexExecutionPort port) {
        return new CodexLaunchService(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                clock,
                new DispatcherInstanceIdentity(),
                port,
                new CodexLifecycleProperties(
                        Duration.ofMinutes(2), Duration.ofMinutes(5), 3));
    }

    private String runStatus(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM dispatcher_run WHERE run_id = ?", String.class, runId);
    }

    private String laneState() {
        return jdbcTemplate.queryForObject("""
                SELECT state FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, String.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", interrupted);
        }
    }
}
