package com.aproject.internal.aidispatcher.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.internal.aidispatcher.config.CodexLifecycleProperties;
import com.aproject.internal.aidispatcher.config.DispatcherProperties;
import com.aproject.internal.aidispatcher.coordination.DispatcherCoordinator;
import com.aproject.internal.aidispatcher.coordination.DispatcherInstanceIdentity;
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
class CodexRecoveryServiceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-07-17T00:00:00Z");
    private static final CodexLifecycleProperties PROPERTIES =
            new CodexLifecycleProperties(Duration.ofMinutes(2), Duration.ofMinutes(5), 3);

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
                SET status = 'READY', external_session_id = 'codex-session-123',
                    bound_at = CURRENT_TIMESTAMP, last_verified_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
    }

    @Test
    void restartRequestsLaunchWhenClaimWasCommittedButHandoffNeverStarted() {
        UUID runId = claimEvent();

        CodexRecoveryResult result = recoveryAt(
                BASE.plus(Duration.ofMinutes(5)), fixedPort(
                        CodexExecutionObservation.unknown("UNUSED", BASE))).recover();

        assertThat(result.outcome()).isEqualTo(CodexRecoveryResult.Outcome.LAUNCH_REQUIRED);
        assertThat(result.runId()).isEqualTo(runId);
        assertThat(runStatus(runId)).isEqualTo("STARTING");
        assertThat(laneState()).isEqualTo("STARTING");
    }

    @Test
    void staleHeartbeatIsReconciledWithTheExternalExecution() {
        UUID runId = startRunningEvent();
        Instant observedAt = BASE.plus(Duration.ofMinutes(8));

        CodexRecoveryResult result = recoveryAt(
                observedAt,
                fixedPort(CodexExecutionObservation.running("execution-1", observedAt))).recover();

        assertThat(result.outcome()).isEqualTo(CodexRecoveryResult.Outcome.RECOVERED_RUNNING);
        assertThat(runStatus(runId)).isEqualTo("RUNNING");
        assertThat(laneState()).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT heartbeat_deadline FROM dispatcher_run WHERE run_id = ?
                """, Timestamp.class, runId).toInstant())
                .isEqualTo(observedAt.plus(Duration.ofMinutes(2)));
    }

    @Test
    void unknownOutcomeUsesBackoffThenPausesWithoutReleasingTheRun() {
        UUID runId = startRunningEvent();
        AtomicInteger queryCount = new AtomicInteger();
        CodexExecutionPort port = new CodexExecutionPort() {
            @Override
            public CodexStartReceipt startCodex(CodexStartCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodexExecutionObservation queryExecution(CodexExecutionQuery query) {
                queryCount.incrementAndGet();
                return CodexExecutionObservation.unknown("PROVIDER_UNAVAILABLE", query.requestedAt());
            }
        };

        CodexRecoveryResult first = recoveryAt(BASE.plus(Duration.ofMinutes(8)), port).recover();
        CodexRecoveryResult duringBackoff =
                recoveryAt(BASE.plus(Duration.ofMinutes(9)), port).recover();
        CodexRecoveryResult second = recoveryAt(BASE.plus(Duration.ofMinutes(13)), port).recover();
        CodexRecoveryResult third = recoveryAt(BASE.plus(Duration.ofMinutes(18)), port).recover();

        assertThat(first.outcome()).isEqualTo(CodexRecoveryResult.Outcome.RETRY_PENDING);
        assertThat(duringBackoff.outcome()).isEqualTo(CodexRecoveryResult.Outcome.RETRY_PENDING);
        assertThat(second.outcome()).isEqualTo(CodexRecoveryResult.Outcome.RETRY_PENDING);
        assertThat(third.outcome()).isEqualTo(CodexRecoveryResult.Outcome.PAUSED);
        assertThat(queryCount).hasValue(3);
        assertThat(runStatus(runId)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(laneState()).isEqualTo("PAUSED");
        assertThat(activeRunId()).isEqualTo(runId);
        assertThat(eventCount("CLAIMED")).isEqualTo(1);
    }

    @Test
    void terminalObservationSettlesThroughTheIdempotentLifecyclePath() {
        UUID runId = startRunningEvent();
        Instant completedAt = BASE.plus(Duration.ofMinutes(8));

        CodexRecoveryResult result = recoveryAt(
                completedAt,
                fixedPort(CodexExecutionObservation.succeeded(
                        "execution-1", "COMPLETED", completedAt))).recover();

        assertThat(result.outcome()).isEqualTo(CodexRecoveryResult.Outcome.COMPLETED);
        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        assertThat(laneState()).isEqualTo("IDLE");
        assertThat(eventCount("CONSUMED")).isEqualTo(1);
    }

    @Test
    void externalRecoveryQueryDoesNotHoldTheLaneLock() throws Exception {
        startRunningEvent();
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CodexExecutionPort port = new CodexExecutionPort() {
            @Override
            public CodexStartReceipt startCodex(CodexStartCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodexExecutionObservation queryExecution(CodexExecutionQuery query) {
                queryEntered.countDown();
                await(releaseQuery);
                return CodexExecutionObservation.running(
                        query.externalExecutionId(), query.requestedAt());
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<CodexRecoveryResult> recovery = executor.submit(
                    () -> recoveryAt(BASE.plus(Duration.ofMinutes(8)), port).recover());
            assertThat(queryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement();
                     ResultSet resultSet = statement.executeQuery("""
                             SELECT state FROM dispatcher_lane
                             WHERE lane_key = 'CODEX_DEVELOPMENT'
                             FOR UPDATE NOWAIT
                             """)) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString(1)).isEqualTo("RECOVERING");
                }
                connection.commit();
            }
            releaseQuery.countDown();
            assertThat(recovery.get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(CodexRecoveryResult.Outcome.RECOVERED_RUNNING);
        } finally {
            releaseQuery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryUsesTheRunSessionSnapshotAfterTheCurrentBindingChanges() {
        startRunningEvent();
        jdbcTemplate.update("""
                UPDATE agent_session
                SET external_session_id = 'replacement-session', version = version + 1,
                    last_verified_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
        AtomicReference<CodexExecutionQuery> capturedQuery = new AtomicReference<>();
        Instant observedAt = BASE.plus(Duration.ofMinutes(8));
        CodexExecutionPort port = new CodexExecutionPort() {
            @Override
            public CodexStartReceipt startCodex(CodexStartCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodexExecutionObservation queryExecution(CodexExecutionQuery query) {
                capturedQuery.set(query);
                return CodexExecutionObservation.running(
                        query.externalExecutionId(), observedAt);
            }
        };

        CodexRecoveryResult result = recoveryAt(observedAt, port).recover();

        assertThat(result.outcome()).isEqualTo(CodexRecoveryResult.Outcome.RECOVERED_RUNNING);
        assertThat(capturedQuery.get().externalSessionId()).isEqualTo("codex-session-123");
    }

    private UUID claimEvent() {
        insertEvent();
        DispatcherCoordinator coordinator = new DispatcherCoordinator(
                jdbcTemplate,
                transactionManager(),
                fixedClock(BASE.plus(Duration.ofMinutes(5))),
                new DispatcherProperties(
                        false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
                        20, 65_536),
                new DispatcherInstanceIdentity());
        return coordinator.tick().runId();
    }

    private UUID startRunningEvent() {
        UUID runId = claimEvent();
        Clock launchClock = fixedClock(BASE.plus(Duration.ofMinutes(5)));
        new CodexLaunchService(
                jdbcTemplate,
                transactionManager(),
                launchClock,
                new DispatcherInstanceIdentity(),
                command -> new CodexStartReceipt("execution-1", Instant.now(launchClock)),
                PROPERTIES).launch(runId);
        return runId;
    }

    private CodexRecoveryService recoveryAt(Instant now, CodexExecutionPort port) {
        Clock recoveryClock = fixedClock(now);
        CodexLifecycleService lifecycleService = new CodexLifecycleService(
                jdbcTemplate, transactionManager(), recoveryClock, PROPERTIES);
        return new CodexRecoveryService(
                jdbcTemplate, transactionManager(), recoveryClock,
                port, lifecycleService, PROPERTIES);
    }

    private CodexExecutionPort fixedPort(CodexExecutionObservation observation) {
        return new CodexExecutionPort() {
            @Override
            public CodexStartReceipt startCodex(CodexStartCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CodexExecutionObservation queryExecution(CodexExecutionQuery query) {
                return observation;
            }
        };
    }

    private void insertEvent() {
        jdbcTemplate.update("""
                INSERT INTO dispatcher_event (
                    source_key, source_event_id, trigger_type, subject_ref,
                    schema_version, occurred_at, recorded_at, processing_state, metadata)
                VALUES ('main-conversation-feed-v1', 'event-1',
                        'line.conversation.recorded', 'line-message:1',
                        1, ?, ?, 'PENDING', '{}'::jsonb)
                """, Timestamp.from(BASE), Timestamp.from(BASE));
    }

    private DataSourceTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }

    private static Clock fixedClock(Instant now) {
        return Clock.fixed(now, ZoneOffset.UTC);
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

    private UUID activeRunId() {
        return jdbcTemplate.queryForObject("""
                SELECT active_run_id FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, UUID.class);
    }

    private long eventCount(String state) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = ?
                """, Long.class, state);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting", interrupted);
        }
    }
}
