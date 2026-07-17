package com.aproject.internal.aidispatcher.coordination;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DispatcherHealthIndicatorIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private DispatcherHealthIndicator indicator;

    @BeforeEach
    void prepareDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("DELETE FROM dispatcher_run_event");
        jdbcTemplate.update("DELETE FROM dispatcher_event");
        jdbcTemplate.update("DELETE FROM dispatcher_run");
        jdbcTemplate.update("""
                UPDATE dispatcher_lane
                SET state = 'IDLE', active_run_id = NULL, consecutive_failure_count = 0,
                    last_error_code = NULL, paused_reason = NULL, updated_at = ?
                WHERE lane_key = 'CODEX_DEVELOPMENT'
                """, Timestamp.from(NOW));
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = 'UNBOUND', external_session_id = NULL, bound_at = NULL,
                    last_verified_at = NULL, version = 0, updated_at = ?
                WHERE session_key = 'development-main'
                """, Timestamp.from(NOW));
        indicator = new DispatcherHealthIndicator(
                jdbcTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unboundSessionIsDegradedWithoutExposingTheTechnicalId() {
        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("sessionStatus", "UNBOUND");
        assertThat(health.getDetails()).doesNotContainKeys(
                "externalSessionId", "sessionId", "technicalSessionId");
    }

    @Test
    void readySessionReportsOnlyNonSensitiveBindingMetadata() {
        jdbcTemplate.update("""
                UPDATE agent_session
                SET status = 'READY', external_session_id = 'secret-thread-id',
                    bound_at = ?, last_verified_at = ?, version = 7, updated_at = ?
                WHERE session_key = 'development-main'
                """, Timestamp.from(NOW.minusSeconds(120)),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("sessionStatus", "READY")
                .containsEntry("sessionProvider", "CODEX_DESKTOP")
                .containsEntry("sessionBindingVersion", 7L)
                .containsEntry("sessionVerificationAgeSeconds", 60L)
                .doesNotContainValue("secret-thread-id");
    }
}
