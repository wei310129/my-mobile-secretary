package com.aproject.internal.aidispatcher.codex.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.internal.aidispatcher.codex.CodexExecutionObservation;
import com.aproject.internal.aidispatcher.codex.CodexExecutionQuery;
import com.aproject.internal.aidispatcher.codex.CodexLaunchResult;
import com.aproject.internal.aidispatcher.codex.CodexLaunchService;
import com.aproject.internal.aidispatcher.codex.CodexLifecycleService;
import com.aproject.internal.aidispatcher.config.CliCodexProperties;
import com.aproject.internal.aidispatcher.config.CodexLifecycleProperties;
import com.aproject.internal.aidispatcher.config.DispatcherProperties;
import com.aproject.internal.aidispatcher.coordination.DispatcherCoordinator;
import com.aproject.internal.aidispatcher.coordination.DispatcherInstanceIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CliCodexExecutionAdapterIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-07-17T08:00:00Z");
    private static final String SESSION_ID = "0199a213-81c0-7800-8aa1-bbab2a035a53";
    private static final CodexLifecycleProperties LIFECYCLE_PROPERTIES =
            new CodexLifecycleProperties(Duration.ofMinutes(2), Duration.ofMinutes(5), 3);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    private Path temporaryDirectory;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private CliCodexProperties properties;
    private CliCodexExecutionAdapter adapter;
    private FakeCodexProcess process;

    @BeforeEach
    void prepare() throws Exception {
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
        jdbcTemplate.update("DELETE FROM codex_execution_attempt");
        jdbcTemplate.update("DELETE FROM dispatcher_run");
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = 'READY', external_session_id = ?,
                    bound_at = CURRENT_TIMESTAMP, last_verified_at = CURRENT_TIMESTAMP,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """, SESSION_ID);
        Path executable = Files.createFile(temporaryDirectory.resolve("codex.exe"));
        Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
        Files.createDirectory(repository.resolve(".git"));
        properties = new CliCodexProperties(
                true, executable.toString(), repository.toString(),
                Duration.ofMillis(50), Duration.ofMinutes(5), Duration.ofHours(2),
                1_048_576, 16_384, 1_048_576);
    }

    @AfterEach
    void closeAdapter() {
        if (process != null) {
            process.release();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    void successfulProcessReturnsImmediatelyThenConsumesTheClaimedBatch() {
        process = fakeProcess(0, """
                {"type":"thread.started","thread_id":"%s"}
                {"type":"turn.started"}
                {"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":20,"output_tokens":30,"reasoning_output_tokens":10}}
                """.formatted(SESSION_ID));
        CodexLaunchResult launch = launchWith(process);

        assertThat(launch.outcome()).isEqualTo(CodexLaunchResult.Outcome.STARTED);
        assertThat(process.isAlive()).isTrue();
        assertThat(runStatus(launch.runId())).isEqualTo("RUNNING");
        await(() -> process.capturedStdin().contains(launch.runId().toString()));

        process.release();
        await(() -> "SUCCEEDED".equals(runStatus(launch.runId())));

        assertThat(eventCount("CONSUMED")).isEqualTo(1);
        assertThat(singleString("SELECT status FROM codex_execution_attempt"))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cli_exit_code FROM codex_execution_attempt", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT input_tokens FROM codex_execution_attempt", Long.class)).isEqualTo(100);
    }

    @Test
    void failedTurnRetainsClaimedEventsAndPausesFailClosed() {
        process = fakeProcess(1, """
                {"type":"thread.started","thread_id":"%s"}
                {"type":"turn.started"}
                {"type":"turn.failed","error":{"message":"simulated"}}
                """.formatted(SESSION_ID));
        CodexLaunchResult launch = launchWith(process);

        process.release();
        await(() -> "OUTCOME_UNKNOWN".equals(runStatus(launch.runId())));

        assertThat(laneState()).isEqualTo("PAUSED");
        assertThat(eventCount("CLAIMED")).isEqualTo(1);
        assertThat(eventCount("PENDING")).isZero();
        assertThat(singleString("SELECT status FROM codex_execution_attempt"))
                .isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void exitBeforeTurnIsAConfirmedFailureAndCanBeRetried() {
        process = fakeProcess(2,
                "{\"type\":\"thread.started\",\"thread_id\":\"" + SESSION_ID + "\"}\n");
        CodexLaunchResult launch = launchWith(process);

        process.release();
        await(() -> "FAILED".equals(runStatus(launch.runId())));

        assertThat(laneState()).isEqualTo("WAITING");
        assertThat(eventCount("PENDING")).isEqualTo(1);
        assertThat(singleString("SELECT status FROM codex_execution_attempt"))
                .isEqualTo("FAILED");
    }

    @Test
    void aNewAdapterCanRecoverADurableProvenTerminalObservation() {
        process = fakeProcess(0, """
                {"type":"thread.started","thread_id":"%s"}
                {"type":"turn.started"}
                {"type":"turn.completed","usage":{}}
                """.formatted(SESSION_ID));
        CodexLaunchResult launch = launchWith(process);
        process.release();
        await(() -> "SUCCEEDED".equals(runStatus(launch.runId())));
        adapter.close();

        CliCodexExecutionAdapter restarted = newAdapter(fakeProcess(0, ""));
        try {
            CodexExecutionObservation observation = restarted.queryExecution(
                    new CodexExecutionQuery(
                            launch.runId(), fencingToken(launch.runId()),
                            launch.externalExecutionId(), SESSION_ID, Instant.now()));
            assertThat(observation.status())
                    .isEqualTo(CodexExecutionObservation.Status.SUCCEEDED);
        } finally {
            restarted.close();
        }
    }

    @Test
    void aNewAdapterTreatsAnUnattachedRunningAuditAsUnknown() {
        process = fakeProcess(0, """
                {"type":"thread.started","thread_id":"%s"}
                {"type":"turn.started"}
                """.formatted(SESSION_ID));
        CodexLaunchResult launch = launchWith(process);
        adapter.close();

        CliCodexExecutionAdapter restarted = newAdapter(fakeProcess(0, ""));
        try {
            CodexExecutionObservation observation = restarted.queryExecution(
                    new CodexExecutionQuery(
                            launch.runId(), fencingToken(launch.runId()),
                            launch.externalExecutionId(), SESSION_ID, Instant.now()));
            assertThat(observation.status())
                    .isEqualTo(CodexExecutionObservation.Status.UNKNOWN);
            assertThat(observation.resultCode()).isEqualTo("CLI_PROCESS_NOT_ATTACHED");
            assertThat(eventCount("CLAIMED")).isEqualTo(1);
        } finally {
            restarted.close();
        }
    }

    @Test
    void aliveProcessWithoutJsonProgressStopsHeartbeatAndPauses() {
        properties = new CliCodexProperties(
                true, properties.executable(), properties.repository(),
                Duration.ofMillis(10), Duration.ofMillis(50), Duration.ofSeconds(5),
                1_048_576, 16_384, 1_048_576);
        process = fakeProcess(0, """
                {"type":"thread.started","thread_id":"%s"}
                {"type":"turn.started"}
                """.formatted(SESSION_ID));

        CodexLaunchResult launch = launchWith(process);
        await(() -> "PAUSED".equals(laneState()));

        assertThat(process.isAlive()).isTrue();
        assertThat(runStatus(launch.runId())).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(eventCount("CLAIMED")).isEqualTo(1);
        assertThat(singleString("SELECT last_error_code FROM dispatcher_lane"))
                .isEqualTo("CLI_NO_PROGRESS_TIMEOUT");
    }

    @Test
    void environmentPolicyRemovesDispatcherSecrets() {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "safe-path");
        environment.put("USERPROFILE", "safe-profile");
        environment.put("AI_DISPATCHER_DB_PASSWORD", "secret-db");
        environment.put("AI_DISPATCHER_SESSION_BINDING_ADMIN_TOKEN", "secret-admin");
        environment.put("UNRELATED_SECRET", "secret-other");

        JavaCodexProcessLauncher.sanitizeEnvironment(environment);

        assertThat(environment)
                .containsEntry("PATH", "safe-path")
                .containsEntry("USERPROFILE", "safe-profile")
                .doesNotContainKeys(
                        "AI_DISPATCHER_DB_PASSWORD",
                        "AI_DISPATCHER_SESSION_BINDING_ADMIN_TOKEN",
                        "UNRELATED_SECRET");
    }

    private CodexLaunchResult launchWith(FakeCodexProcess fakeProcess) {
        insertEvent();
        Clock coordinatorClock = Clock.fixed(BASE, ZoneOffset.UTC);
        DispatcherCoordinator coordinator = new DispatcherCoordinator(
                jdbcTemplate, transactionManager(), coordinatorClock,
                new DispatcherProperties(
                        false, Duration.ofSeconds(1),
                        Duration.ofMinutes(5), Duration.ofMinutes(30)),
                new DispatcherInstanceIdentity());
        UUID runId = coordinator.tick().runId();
        if (runId == null) {
            runId = coordinator.tick().runId();
        }
        assertThat(runId).isNotNull();
        adapter = newAdapter(fakeProcess);
        CodexLaunchService launchService = new CodexLaunchService(
                jdbcTemplate, transactionManager(), Clock.systemUTC(),
                new DispatcherInstanceIdentity(), adapter, LIFECYCLE_PROPERTIES);
        return launchService.launch(runId);
    }

    private CliCodexExecutionAdapter newAdapter(FakeCodexProcess fakeProcess) {
        return new CliCodexExecutionAdapter(
                properties, new ObjectMapper(),
                new CodexLifecycleService(
                        jdbcTemplate, transactionManager(), Clock.systemUTC(), LIFECYCLE_PROPERTIES),
                Clock.systemUTC(), ignored -> fakeProcess,
                new CodexExecutionAuditRepository(jdbcTemplate),
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor());
    }

    private FakeCodexProcess fakeProcess(int exitCode, String stdout) {
        return new FakeCodexProcess(exitCode, stdout);
    }

    private void insertEvent() {
        Instant recordedAt = BASE.minus(Duration.ofMinutes(10));
        jdbcTemplate.update("""
                INSERT INTO dispatcher_event (
                    source_key, source_event_id, trigger_type, subject_ref,
                    schema_version, occurred_at, recorded_at, processing_state, metadata)
                VALUES ('main-conversation-feed-v1', 'event-1',
                        'line.conversation.recorded', 'line-message:1',
                        1, ?, ?, 'PENDING', '{"text":"request"}'::jsonb)
                """, Timestamp.from(recordedAt), Timestamp.from(recordedAt));
    }

    private DataSourceTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }

    private String runStatus(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM dispatcher_run WHERE run_id = ?", String.class, runId);
    }

    private long fencingToken(UUID runId) {
        return jdbcTemplate.queryForObject(
                "SELECT fencing_token FROM dispatcher_run WHERE run_id = ?", Long.class, runId);
    }

    private String laneState() {
        return singleString("SELECT state FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT'");
    }

    private long eventCount(String state) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatcher_event WHERE processing_state = ?",
                Long.class, state);
    }

    private String singleString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for async result", interrupted);
            }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static final class FakeCodexProcess implements CodexManagedProcess {
        private final int exitCode;
        private final InputStream stdout;
        private final InputStream stderr = new ByteArrayInputStream(new byte[0]);
        private final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean alive = true;

        private FakeCodexProcess(int exitCode, String stdout) {
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public long pid() {
            return 4242;
        }

        @Override
        public OutputStream standardInput() {
            return stdin;
        }

        @Override
        public InputStream standardOutput() {
            return stdout;
        }

        @Override
        public InputStream standardError() {
            return stderr;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public int waitFor() throws InterruptedException {
            release.await();
            alive = false;
            return exitCode;
        }

        @Override
        public void destroyBeforeDispatch() {
            release();
        }

        private void release() {
            alive = false;
            release.countDown();
        }

        private String capturedStdin() {
            return stdin.toString(StandardCharsets.UTF_8);
        }
    }
}
