package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class WorkspaceMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("workspace_migration");

    @Test
    void v18PreservesSingletonsAndContinuesIdentityAfterExistingMaximum() throws Exception {
        migrateTo("17");
        execute("""
                INSERT INTO conversation_context (id, updated_at) VALUES (7, CURRENT_TIMESTAMP);
                INSERT INTO planning_preference
                    (id, extra_transfer_minutes, meal_buffer_minutes, updated_at)
                    VALUES (11, 0, 0, CURRENT_TIMESTAMP);
                INSERT INTO reminder_preference
                    (id, allow_high_priority, updated_at)
                    VALUES (13, TRUE, CURRENT_TIMESTAMP);
                """);

        migrateTo("18");

        assertThat(uuid("SELECT workspace_id FROM conversation_context WHERE id = 7"))
                .isEqualTo(LegacyAccountIds.WORKSPACE_ID);
        assertThat(uuid("SELECT created_by_user_id FROM planning_preference WHERE id = 11"))
                .isEqualTo(LegacyAccountIds.USER_ID);

        UUID userId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID workspaceId = UUID.fromString("10000000-0000-0000-0000-000000000101");
        execute("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES ('%s', 'Second user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES ('%s', 'Second household', 'HOUSEHOLD', '%s',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
                """.formatted(userId, workspaceId, userId));

        assertThat(insertAndReturnId("""
                INSERT INTO conversation_context (workspace_id, created_by_user_id, updated_at)
                VALUES ('%s', '%s', CURRENT_TIMESTAMP) RETURNING id
                """.formatted(workspaceId, userId))).isEqualTo(8);
        assertThat(insertAndReturnId("""
                INSERT INTO planning_preference
                    (workspace_id, created_by_user_id, extra_transfer_minutes,
                     meal_buffer_minutes, updated_at)
                VALUES ('%s', '%s', 0, 0, CURRENT_TIMESTAMP) RETURNING id
                """.formatted(workspaceId, userId))).isEqualTo(12);
        assertThat(insertAndReturnId("""
                INSERT INTO reminder_preference
                    (workspace_id, created_by_user_id, allow_high_priority, updated_at)
                VALUES ('%s', '%s', TRUE, CURRENT_TIMESTAMP) RETURNING id
                """.formatted(workspaceId, userId))).isEqualTo(14);
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int insertAndReturnId(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static UUID uuid(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getObject(1, UUID.class);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
