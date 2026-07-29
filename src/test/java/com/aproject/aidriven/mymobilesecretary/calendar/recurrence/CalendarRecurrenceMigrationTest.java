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
class CalendarRecurrenceMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("calendar_recurrence_migration");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "conversation_scope_current_key_version",
                        "1",
                        "conversation_scope_hmac_key_base64",
                        "dGVzdC1jYWxlbmRhci1yZWN1cnJlbmNlLW1pZ3JhdGlvbi1rZXk="))
                .load()
                .migrate();
    }

    @Test
    void v89CreatesTypedSeriesRevisionExceptionAndAuditTables() throws Exception {
        assertThat(strings(
                        """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name LIKE 'calendar_recurrence_%'
                        ORDER BY table_name
                        """))
                .containsExactly(
                        "calendar_recurrence_exception",
                        "calendar_recurrence_rule_revision",
                        "calendar_recurrence_series",
                        "calendar_recurrence_split_audit");

        assertThat(strings(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'calendar_recurrence_rule_revision'
                          AND column_name IN (
                              'frequency', 'recurrence_interval', 'weekdays',
                              'timed_anchor', 'all_day_anchor', 'end_kind',
                              'effective_from_timed', 'effective_from_date')
                        ORDER BY column_name
                        """))
                .containsExactly(
                        "all_day_anchor",
                        "effective_from_date",
                        "effective_from_timed",
                        "end_kind",
                        "frequency",
                        "recurrence_interval",
                        "timed_anchor",
                        "weekdays");
    }

    @Test
    void v89EnforcesCompositeSourceIdentityAndSingleActiveRevision() throws Exception {
        assertThat(strings(
                        """
                        SELECT conname || '=' || pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_recurrence_series_plan',
                            'fk_calendar_recurrence_series_activity',
                            'chk_calendar_recurrence_anchor',
                            'chk_calendar_recurrence_end',
                            'chk_calendar_recurrence_effective_from')
                        ORDER BY conname
                        """))
                .hasSize(5)
                .allSatisfy(value -> assertThat(value).doesNotContain("RRULE"));

        assertThat(strings(
                        """
                        SELECT indexname || '=' || indexdef
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN (
                              'uq_calendar_recurrence_single_active_revision',
                              'uq_calendar_recurrence_plan_owner',
                              'uq_calendar_recurrence_activity_owner')
                        ORDER BY indexname
                        """))
                .hasSize(3)
                .anySatisfy(value -> assertThat(value)
                        .startsWith("uq_calendar_recurrence_single_active_revision=")
                        .contains("WHERE")
                        .contains("state")
                        .contains("'ACTIVE'"));
    }

    @Test
    void v89ForcesRlsThroughEffectiveOwnerAndMakesAuditAppendOnly() throws Exception {
        assertThat(strings(
                        """
                        SELECT relname || '=' || relrowsecurity::text || ':' ||
                               relforcerowsecurity::text
                        FROM pg_class
                        WHERE relname IN (
                            'calendar_recurrence_series',
                            'calendar_recurrence_rule_revision',
                            'calendar_recurrence_exception',
                            'calendar_recurrence_split_audit')
                        ORDER BY relname
                        """))
                .containsExactly(
                        "calendar_recurrence_exception=true:true",
                        "calendar_recurrence_rule_revision=true:true",
                        "calendar_recurrence_series=true:true",
                        "calendar_recurrence_split_audit=true:true");

        assertThat(strings(
                        """
                        SELECT policyname || '=' || cmd
                        FROM pg_policies
                        WHERE schemaname = 'public'
                          AND tablename LIKE 'calendar_recurrence_%'
                        ORDER BY policyname
                        """))
                .containsExactly(
                        "rls_calendar_recurrence_audit_effective_owner=SELECT",
                        "rls_calendar_recurrence_exception_effective_owner=ALL",
                        "rls_calendar_recurrence_revision_effective_owner=ALL",
                        "rls_calendar_recurrence_series_effective_owner=ALL");

        assertThat(strings(
                        """
                        SELECT tgname || '=' || pg_get_triggerdef(oid)
                        FROM pg_trigger
                        WHERE tgrelid = 'calendar_recurrence_split_audit'::regclass
                          AND NOT tgisinternal
                        """))
                .singleElement()
                .satisfies(value -> assertThat(value)
                        .startsWith("trg_calendar_recurrence_audit_append_only=")
                        .contains("BEFORE")
                        .contains("UPDATE")
                        .contains("DELETE"));
    }

    @Test
    void v89IsTheLatestAppliedMigration() throws Exception {
        assertThat(strings(
                        """
                        SELECT max(version::integer)::text
                        FROM flyway_schema_history
                        WHERE success
                        """))
                .containsExactly("89");
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
