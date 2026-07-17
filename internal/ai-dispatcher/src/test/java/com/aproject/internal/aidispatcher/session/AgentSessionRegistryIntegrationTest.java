package com.aproject.internal.aidispatcher.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
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
class AgentSessionRegistryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final String FIRST_SESSION_ID =
            "0199a213-81c0-7800-8aa1-bbab2a035a53";
    private static final String SECOND_SESSION_ID =
            "0199a213-81c0-7800-8aa1-bbab2a035a54";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private AgentSessionRegistry registry;

    @BeforeEach
    void prepareDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'IDLE', active_run_id = NULL, fencing_token = 0,
                    observed_first_pending_at = NULL, observed_last_pending_at = NULL,
                    eligible_at = NULL, retry_not_before = NULL,
                    consecutive_failure_count = 0, last_error_code = NULL,
                    paused_reason = NULL, version = 0, updated_at = CURRENT_TIMESTAMP
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """);
        jdbcTemplate.update("DELETE FROM dispatcher_run_event");
        jdbcTemplate.update("DELETE FROM dispatcher_event");
        jdbcTemplate.update("DELETE FROM dispatcher_run");
        jdbcTemplate.update("DELETE FROM agent_session_binding_audit");
        jdbcTemplate.update("""
                UPDATE agent_session
                SET display_name = '開發主要對話', status = 'UNBOUND',
                    external_session_id = NULL, bound_at = NULL, last_verified_at = NULL,
                    version = 0, updated_at = CURRENT_TIMESTAMP
                WHERE session_key = 'development-main'
                """);
        registry = new AgentSessionRegistry(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void migrationCreatesTheNamedUnboundSession() {
        SessionBinding binding = registry.developmentSession();

        assertThat(binding.sessionKey()).isEqualTo("development-main");
        assertThat(binding.displayName()).isEqualTo("開發主要對話");
        assertThat(binding.provider()).isEqualTo("CODEX_DESKTOP");
        assertThat(binding.status()).isEqualTo(AgentSessionStatus.UNBOUND);
        assertThat(binding.externalSessionId()).isNull();
    }

    @Test
    void bindingIsAuditedAndResumesOnlyASessionReadinessPause() {
        insertPendingEvent();
        pauseLane("SESSION_NOT_READY");

        SessionBinding binding = registry.bindDevelopmentSession(
                new BindDevelopmentSessionCommand(
                        FIRST_SESSION_ID, 0L, "operator-a", "Initial desktop thread binding"));

        assertThat(binding.status()).isEqualTo(AgentSessionStatus.READY);
        assertThat(binding.externalSessionId()).isEqualTo(FIRST_SESSION_ID);
        assertThat(binding.version()).isEqualTo(1);
        assertThat(binding.boundAt()).isEqualTo(NOW);
        assertThat(binding.lastVerifiedAt()).isEqualTo(NOW);
        assertThat(laneState()).isEqualTo("WAITING");
        assertThat(singleString("SELECT action FROM agent_session_binding_audit"))
                .isEqualTo("BOUND");
        assertThat(singleString("SELECT actor_id FROM agent_session_binding_audit"))
                .isEqualTo("operator-a");
    }

    @Test
    void rebindingUsesOptimisticVersionAndPreservesHistory() {
        SessionBinding first = registry.bindDevelopmentSession(new BindDevelopmentSessionCommand(
                FIRST_SESSION_ID, 0L, "operator-a", null));

        assertThatThrownBy(() -> registry.bindDevelopmentSession(
                new BindDevelopmentSessionCommand(
                        SECOND_SESSION_ID, 0L, "operator-b", "stale browser form")))
                .isInstanceOf(SessionBindingConflictException.class)
                .hasMessageContaining("expected 0 but found 1");

        SessionBinding second = registry.bindDevelopmentSession(
                new BindDevelopmentSessionCommand(
                        SECOND_SESSION_ID, first.version(), "operator-b", "Replace old thread"));

        assertThat(second.externalSessionId()).isEqualTo(SECOND_SESSION_ID);
        assertThat(second.version()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_session_binding_audit", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT action FROM agent_session_binding_audit ORDER BY binding_version
                """, String.class)).containsExactly("BOUND", "REBOUND");
    }

    @Test
    void cannotRebindWhenPausedRunOutcomeIsStillUnknown() {
        SessionBinding binding = registry.bindDevelopmentSession(FIRST_SESSION_ID);
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO dispatcher_run (
                    run_id, lane_key, run_sequence, fencing_token, status, session_key,
                    starter_instance_id, event_count, start_requested_at,
                    recovery_attempt_count, created_at, updated_at)
                VALUES (?, 'CODEX_DEVELOPMENT', 1, 1, 'OUTCOME_UNKNOWN',
                        'development-main', 'instance-a', 1, ?, 3, ?, ?)
                """, runId, timestamp(NOW), timestamp(NOW), timestamp(NOW));
        jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'PAUSED', active_run_id = ?, fencing_token = 1,
                    last_error_code = 'CODEX_OUTCOME_UNCONFIRMED'
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, runId);

        assertThatThrownBy(() -> registry.bindDevelopmentSession(
                new BindDevelopmentSessionCommand(
                        SECOND_SESSION_ID, binding.version(), "operator-b", null)))
                .isInstanceOf(SessionBindingConflictException.class)
                .hasMessageContaining("run outcome may still be active");
        assertThat(registry.developmentSession().externalSessionId())
                .isEqualTo(FIRST_SESSION_ID);
    }

    @Test
    void unbindingIsAuditedAndPausesTheLane() {
        SessionBinding bound = registry.bindDevelopmentSession(FIRST_SESSION_ID);

        SessionBinding unbound = registry.unbindDevelopmentSession(
                new UnbindDevelopmentSessionCommand(
                        bound.version(), "operator-a", "Thread was archived"));

        assertThat(unbound.status()).isEqualTo(AgentSessionStatus.UNBOUND);
        assertThat(unbound.externalSessionId()).isNull();
        assertThat(unbound.boundAt()).isNull();
        assertThat(laneState()).isEqualTo("PAUSED");
        assertThat(singleString("SELECT last_error_code FROM dispatcher_lane"))
                .isEqualTo("SESSION_NOT_READY");
        assertThat(jdbcTemplate.queryForList("""
                SELECT action FROM agent_session_binding_audit ORDER BY binding_version
                """, String.class)).containsExactly("BOUND", "UNBOUND");
    }

    @Test
    void bindingDoesNotClearAnUnrelatedPause() {
        pauseLane("OPERATOR_PAUSE");

        registry.bindDevelopmentSession(FIRST_SESSION_ID);

        assertThat(laneState()).isEqualTo("PAUSED");
        assertThat(singleString("SELECT last_error_code FROM dispatcher_lane"))
                .isEqualTo("OPERATOR_PAUSE");
    }

    @Test
    void rejectsDisplayNamesInPlaceOfOpaqueTechnicalIds() {
        assertThatThrownBy(() -> registry.bindDevelopmentSession("開發主要對話"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opaque technical id");
    }

    private void insertPendingEvent() {
        jdbcTemplate.update("""
                INSERT INTO dispatcher_event (
                    source_key, source_event_id, trigger_type, subject_ref,
                    schema_version, occurred_at, recorded_at, processing_state, metadata)
                VALUES ('main-conversation-feed-v1', 'event-1',
                        'line.conversation.recorded', 'line-message:event-1',
                        1, ?, ?, 'PENDING', '{}'::jsonb)
                """, timestamp(NOW), timestamp(NOW));
    }

    private void pauseLane(String errorCode) {
        jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'PAUSED', active_run_id = NULL,
                    last_error_code = ?, paused_reason = 'test pause'
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, errorCode);
    }

    private String laneState() {
        return singleString("""
                SELECT state FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT'
                """);
    }

    private String singleString(String sql) {
        return jdbcTemplate.queryForObject(sql, String.class);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
