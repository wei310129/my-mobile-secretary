package com.aproject.internal.aidispatcher.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.internal.aidispatcher.config.CodexLifecycleProperties;
import com.aproject.internal.aidispatcher.config.DispatcherRetentionProperties;
import com.aproject.internal.aidispatcher.config.DispatcherProperties;
import com.aproject.internal.aidispatcher.coordination.DispatcherCoordinator;
import com.aproject.internal.aidispatcher.coordination.DispatcherInstanceIdentity;
import com.aproject.internal.aidispatcher.retention.DispatcherDataRetentionService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
class CodexLifecycleServiceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-07-17T00:00:00Z");
    private static final CodexLifecycleProperties LIFECYCLE_PROPERTIES =
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
    void successfulFinishConsumesOnlyTheRunBatchAndWaitsForNewEvents() {
        UUID runId = startRunningEvent("event-1");
        long token = fencingToken(runId);
        insertEvent("event-2", BASE.plus(Duration.ofMinutes(5)));
        CodexLifecycleService service = lifecycleAt(BASE.plus(Duration.ofMinutes(6)));

        CodexLifecycleResult result = service.onCodexFinish(
                runId, token,
                new CodexCompletion(
                        CodexCompletion.Status.SUCCEEDED,
                        "COMPLETED",
                        BASE.plus(Duration.ofMinutes(6))));

        assertThat(result.outcome()).isEqualTo(CodexLifecycleResult.Outcome.COMPLETED);
        assertThat(result.pendingEventCount()).isEqualTo(1);
        assertThat(eventCount("CONSUMED")).isEqualTo(1);
        assertThat(eventCount("PENDING")).isEqualTo(1);
        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
        assertThat(laneState()).isEqualTo("WAITING");

        CodexLifecycleResult duplicate = service.onCodexFinish(
                runId, token,
                new CodexCompletion(
                        CodexCompletion.Status.SUCCEEDED,
                        "COMPLETED",
                        BASE.plus(Duration.ofMinutes(6))));
        assertThat(duplicate.outcome()).isEqualTo(CodexLifecycleResult.Outcome.DUPLICATE);
        assertThat(eventCount("CONSUMED")).isEqualTo(1);
    }

    @Test
    void failedFinishReleasesTheBatchAndAppliesRetryBackoff() {
        UUID runId = startRunningEvent("event-1");
        long token = fencingToken(runId);
        Instant failedAt = BASE.plus(Duration.ofMinutes(6));

        CodexLifecycleResult result = lifecycleAt(failedAt).onCodexFinish(
                runId, token,
                new CodexCompletion(CodexCompletion.Status.FAILED, "PROCESS_EXITED", failedAt));

        assertThat(result.outcome()).isEqualTo(CodexLifecycleResult.Outcome.COMPLETED);
        assertThat(eventCount("PENDING")).isEqualTo(1);
        assertThat(runStatus(runId)).isEqualTo("FAILED");
        assertThat(laneState()).isEqualTo("WAITING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT retry_not_before FROM dispatcher_lane
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, Timestamp.class).toInstant()).isEqualTo(failedAt.plus(Duration.ofMinutes(5)));
    }

    @Test
    void heartbeatExtendsTheDeadlineOnlyForTheActiveFencingToken() {
        UUID runId = startRunningEvent("event-1");
        long token = fencingToken(runId);
        Instant heartbeatAt = BASE.plus(Duration.ofMinutes(6));
        CodexLifecycleService service = lifecycleAt(heartbeatAt);

        CodexLifecycleResult accepted = service.heartbeat(runId, token);
        CodexLifecycleResult stale = service.heartbeat(runId, token + 1);

        assertThat(accepted.outcome())
                .isEqualTo(CodexLifecycleResult.Outcome.HEARTBEAT_ACCEPTED);
        assertThat(stale.outcome())
                .isEqualTo(CodexLifecycleResult.Outcome.HEARTBEAT_REJECTED);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT heartbeat_deadline FROM dispatcher_run WHERE run_id = ?
                """, Timestamp.class, runId).toInstant())
                .isEqualTo(heartbeatAt.plus(Duration.ofMinutes(2)));
    }

    @Test
    void staleFinishCannotSettleTheActiveRun() {
        UUID runId = startRunningEvent("event-1");
        long token = fencingToken(runId);

        CodexLifecycleResult stale = lifecycleAt(BASE.plus(Duration.ofMinutes(6))).onCodexFinish(
                runId, token + 1,
                new CodexCompletion(
                        CodexCompletion.Status.SUCCEEDED,
                        "STALE",
                        BASE.plus(Duration.ofMinutes(6))));

        assertThat(stale.outcome()).isEqualTo(CodexLifecycleResult.Outcome.STALE);
        assertThat(runStatus(runId)).isEqualTo("RUNNING");
        assertThat(eventCount("CLAIMED")).isEqualTo(1);
    }

    @Test
    void retentionPurgesOnlyAnExpiredConsumedPayloadWhilePreservingAuditIdentity() {
        UUID runId = startRunningEvent("event-1");
        long token = fencingToken(runId);
        Instant completedAt = BASE.plus(Duration.ofMinutes(6));
        lifecycleAt(completedAt).onCodexFinish(
                runId, token,
                new CodexCompletion(CodexCompletion.Status.SUCCEEDED, "COMPLETED", completedAt));
        DispatcherDataRetentionService retention = new DispatcherDataRetentionService(
                jdbcTemplate,
                Clock.fixed(completedAt.plus(Duration.ofDays(91)), ZoneOffset.UTC),
                new DispatcherRetentionProperties(Duration.ofDays(90), Duration.ofHours(1)));

        int purged = retention.purgeExpiredConsumedPayloads();

        assertThat(purged).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT metadata::text FROM dispatcher_event WHERE source_event_id = 'event-1'
                """, String.class)).isEqualTo("{}");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload_purged_at IS NOT NULL
                FROM dispatcher_event WHERE source_event_id = 'event-1'
                """, Boolean.class)).isTrue();
        assertThat(runStatus(runId)).isEqualTo("SUCCEEDED");
    }

    private UUID startRunningEvent(String eventId) {
        insertEvent(eventId, BASE);
        Clock launchClock = Clock.fixed(BASE.plus(Duration.ofMinutes(5)), ZoneOffset.UTC);
        DispatcherCoordinator coordinator = new DispatcherCoordinator(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                launchClock,
                new DispatcherProperties(
                        false, Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(30)),
                new DispatcherInstanceIdentity());
        UUID runId = coordinator.tick().runId();
        CodexExecutionPort port = command -> new CodexStartReceipt(
                "execution-" + eventId, BASE.plus(Duration.ofMinutes(5)));
        new CodexLaunchService(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                launchClock,
                new DispatcherInstanceIdentity(),
                port,
                LIFECYCLE_PROPERTIES).launch(runId);
        return runId;
    }

    private CodexLifecycleService lifecycleAt(Instant now) {
        return new CodexLifecycleService(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(now, ZoneOffset.UTC),
                LIFECYCLE_PROPERTIES);
    }

    private void insertEvent(String eventId, Instant recordedAt) {
        jdbcTemplate.update("""
                INSERT INTO dispatcher_event (
                    source_key, source_event_id, trigger_type, subject_ref,
                    schema_version, occurred_at, recorded_at, processing_state, metadata)
                VALUES ('main-conversation-feed-v1', ?,
                        'line.conversation.recorded', ?, 1, ?, ?, 'PENDING',
                        '{"text":"request"}'::jsonb)
                """, eventId, "line-message:" + eventId,
                Timestamp.from(recordedAt), Timestamp.from(recordedAt));
    }

    private long fencingToken(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT fencing_token FROM dispatcher_run WHERE run_id = ?",
                Long.class,
                runId);
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

    private long eventCount(String state) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = ?
                """, Long.class, state);
    }
}
