package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class CalendarRecurrenceProjectionMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("calendar_recurrence_projection");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "conversation_scope_current_key_version",
                        "1",
                        "conversation_scope_hmac_key_base64",
                        "dGVzdC1jYWxlbmRhci1yZWN1cnJlbmNlLXByb2plY3Rpb24ta2V5"))
                .load()
                .migrate();
    }

    @Test
    void v90CreatesRevisionBoundProjectionTables() throws Exception {
        assertThat(strings(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                              'calendar_recurrence_adoption',
                              'calendar_recurrence_participation_exception',
                              'calendar_recurrence_node_review',
                              'calendar_recurrence_reminder_materialization',
                              'calendar_recurrence_reminder_cursor')
                        ORDER BY table_name
                        """))
                .containsExactly(
                        "calendar_recurrence_adoption",
                        "calendar_recurrence_node_review",
                        "calendar_recurrence_participation_exception",
                        "calendar_recurrence_reminder_cursor",
                        "calendar_recurrence_reminder_materialization");

        assertThat(strings(
                        """
                        SELECT table_name || '.' || column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND column_name IN (
                              'accepted_rule_revision',
                              'logical_timed_start',
                              'logical_all_day_start',
                              'recurrence_rule_revision',
                              'horizon_exclusive')
                          AND table_name LIKE 'calendar_recurrence_%'
                        ORDER BY table_name, column_name
                        """))
                .contains(
                        "calendar_recurrence_adoption.accepted_rule_revision",
                        "calendar_recurrence_adoption.logical_all_day_start",
                        "calendar_recurrence_adoption.logical_timed_start",
                        "calendar_recurrence_participation_exception.accepted_rule_revision",
                        "calendar_recurrence_reminder_cursor.horizon_exclusive",
                        "calendar_recurrence_reminder_materialization.recurrence_rule_revision");
    }

    @Test
    void v90ForcesActorPrivateRlsAndStableIdentities() throws Exception {
        assertThat(strings(
                        """
                        SELECT relname || '=' || relrowsecurity::text || ':' ||
                               relforcerowsecurity::text
                        FROM pg_class
                        WHERE relname IN (
                            'calendar_recurrence_adoption',
                            'calendar_recurrence_participation_exception',
                            'calendar_recurrence_node_review',
                            'calendar_recurrence_reminder_materialization',
                            'calendar_recurrence_reminder_cursor')
                        ORDER BY relname
                        """))
                .allSatisfy(value -> assertThat(value).endsWith("=true:true"))
                .hasSize(5);
        assertThat(strings(
                        """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN (
                              'uq_calendar_recurrence_adoption_actor_scope',
                              'uq_calendar_recurrence_participation_actor_key',
                              'uq_calendar_recurrence_reminder_delivery')
                        ORDER BY indexname
                        """))
                .containsExactly(
                        "uq_calendar_recurrence_adoption_actor_scope",
                        "uq_calendar_recurrence_participation_actor_key",
                        "uq_calendar_recurrence_reminder_delivery");
    }

    @Test
    void v91AddsRecurringRegistrationScopeAndActorPrivateState() throws Exception {
        assertThat(strings(
                        """
                        SELECT relname || '=' || relrowsecurity::text || ':' ||
                               relforcerowsecurity::text
                        FROM pg_class
                        WHERE relname IN (
                            'calendar_recurring_registration_policy',
                            'calendar_recurring_capacity_bucket',
                            'calendar_recurring_registration')
                        ORDER BY relname
                        """))
                .allSatisfy(value -> assertThat(value).endsWith("=true:true"))
                .hasSize(3);
        assertThat(strings(
                        """
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname = 'uq_calendar_recurring_bucket_key'
                        """))
                .containsExactly("uq_calendar_recurring_bucket_key");
    }

    @Test
    void v91IsLatest() throws Exception {
        assertThat(strings(
                        """
                        SELECT max(version::integer)::text
                        FROM flyway_schema_history
                        WHERE success
                        """))
                .containsExactly("91");
    }

    private static List<String> strings(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            var result = new ArrayList<String>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        }
    }
}
