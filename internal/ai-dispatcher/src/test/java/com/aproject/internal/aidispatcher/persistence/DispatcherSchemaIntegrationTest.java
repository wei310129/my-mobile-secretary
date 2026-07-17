package com.aproject.internal.aidispatcher.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DispatcherSchemaIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @AfterEach
    void resetMutableRows() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE dispatcher_lane
                    SET state = 'IDLE', active_run_id = NULL, fencing_token = 0,
                        consecutive_failure_count = 0, version = version + 1,
                        observed_first_pending_at = NULL, observed_last_pending_at = NULL,
                        eligible_at = NULL, retry_not_before = NULL,
                        last_error_code = NULL, paused_reason = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    """);
            statement.executeUpdate("DELETE FROM dispatcher_run_event");
            statement.executeUpdate("DELETE FROM dispatcher_event");
            statement.executeUpdate("DELETE FROM dispatcher_run");
        }
    }

    @Test
    void createsOnlyDispatcherOwnedCoreTablesAndSeedsSingletons() throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                     """)) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }

        assertThat(tables).contains(
                "agent_session",
                "agent_session_binding_audit",
                "dispatcher_event",
                "dispatcher_lane",
                "dispatcher_run",
                "dispatcher_run_event",
                "trigger_cursor");
        assertThat(singleString("SELECT display_name FROM agent_session WHERE session_key = 'development-main'"))
                .isEqualTo("開發主要對話");
        assertThat(singleString("SELECT state FROM dispatcher_lane WHERE lane_key = 'CODEX_DEVELOPMENT'"))
                .isEqualTo("IDLE");
    }

    @Test
    void databaseRejectsASecondActiveRun() throws SQLException {
        insertRun(UUID.randomUUID(), 1, 1, "STARTING");

        assertThatThrownBy(() -> insertRun(UUID.randomUUID(), 2, 2, "RUNNING"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_dispatcher_single_active_run");
    }

    @Test
    void databaseRejectsClaimedEventWithoutAnActiveRun() {
        assertThatThrownBy(() -> {
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO dispatcher_event (
                            source_key, source_event_id, trigger_type, subject_ref,
                            schema_version, occurred_at, recorded_at, processing_state)
                        VALUES (
                            'main-conversation-feed-v1', 'event-1',
                            'line.conversation.recorded', 'line-message:1',
                            1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'CLAIMED')
                        """);
            }
        }).isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_dispatcher_event_state");
    }

    private static void insertRun(UUID runId, long sequence, long fencingToken, String status)
            throws SQLException {
        try (Connection connection = connection(); var statement = connection.prepareStatement("""
                INSERT INTO dispatcher_run (
                    run_id, lane_key, run_sequence, fencing_token, status, session_key,
                    starter_instance_id, event_count, start_requested_at,
                    recovery_attempt_count, created_at, updated_at)
                VALUES (?, 'CODEX_DEVELOPMENT', ?, ?, ?, 'development-main',
                        'schema-test', 1, CURRENT_TIMESTAMP, 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """)) {
            statement.setObject(1, runId);
            statement.setLong(2, sequence);
            statement.setLong(3, fencingToken);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    private static String singleString(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
