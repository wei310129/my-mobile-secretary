package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Tag("migration")
class CalendarSharedAdoptionMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("calendar_shared_adoption_migration");

    @BeforeAll
    static void migrateToV83() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .placeholders(Map.of(
                        "conversation_scope_current_key_version",
                        "1",
                        "conversation_scope_hmac_key_base64",
                        "dGVzdC1jYWxlbmRhci1zaGFyZWQtYWRvcHRpb24ta2V5"))
                .load()
                .migrate();
    }

    @Test
    void v83MakesSourceOwnershipRequiredAndReferencesTheSourcePlanAndNode()
            throws Exception {
        assertThat(strings(
                        """
                        SELECT table_name || '.' || column_name || '=' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND (table_name, column_name) IN (
                              ('calendar_adoption', 'source_created_by_user_id'),
                              ('calendar_adoption_node', 'source_created_by_user_id'))
                        ORDER BY table_name
                        """))
                .containsExactly(
                        "calendar_adoption.source_created_by_user_id=NO",
                        "calendar_adoption_node.source_created_by_user_id=NO");

        assertThat(strings(
                        """
                        SELECT conname || '=' || pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_adoption_source_plan',
                            'fk_calendar_adoption_node_source_node')
                        ORDER BY conname
                        """))
                .containsExactly(
                        "fk_calendar_adoption_node_source_node="
                                + "FOREIGN KEY (node_id, plan_id, workspace_id, "
                                + "source_created_by_user_id) REFERENCES "
                                + "calendar_time_node(id, plan_id, workspace_id, "
                                + "created_by_user_id)",
                        "fk_calendar_adoption_source_plan="
                                + "FOREIGN KEY (plan_id, workspace_id, "
                                + "source_created_by_user_id) REFERENCES "
                                + "calendar_plan(id, workspace_id, created_by_user_id)");
    }

    @Test
    void v83ProtectsHistoryWithForcedRlsAndAnAppendOnlyTrigger() throws Exception {
        assertThat(strings(
                        """
                        SELECT relrowsecurity::text || ':' || relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid = 'public.calendar_adoption_history'::regclass
                        """))
                .containsExactly("true:true");
        assertThat(strings(
                        """
                        SELECT policyname || '=' || cmd
                        FROM pg_policies
                        WHERE schemaname = 'public'
                          AND tablename = 'calendar_adoption_history'
                        ORDER BY policyname
                        """))
                .containsExactly(
                        "rls_calendar_adoption_history_actor_insert=INSERT",
                        "rls_calendar_adoption_history_actor_select=SELECT");
        assertThat(strings(
                        """
                        SELECT tgname || '=' || pg_get_triggerdef(oid)
                        FROM pg_trigger
                        WHERE tgrelid = 'public.calendar_adoption_history'::regclass
                          AND NOT tgisinternal
                        """))
                .singleElement()
                .satisfies(trigger -> {
                    assertThat(trigger)
                            .startsWith("trg_calendar_adoption_history_append_only=")
                            .contains("BEFORE")
                            .contains("UPDATE")
                            .contains("DELETE")
                            .contains("reject_calendar_adoption_history_mutation()");
                });
    }

    @Test
    void v83AddsWholePlanViewerSelectPoliciesForPlanDescendants() throws Exception {
        assertThat(strings(
                        """
                        SELECT tablename || '.' || policyname || '=' || cmd
                        FROM pg_policies
                        WHERE schemaname = 'public'
                          AND policyname IN (
                              'rls_calendar_activity_whole_plan_recipient',
                              'rls_calendar_time_node_whole_plan_recipient')
                        ORDER BY tablename
                        """))
                .containsExactly(
                        "calendar_activity.rls_calendar_activity_whole_plan_recipient=SELECT",
                        "calendar_time_node.rls_calendar_time_node_whole_plan_recipient=SELECT");
    }

    @Test
    void v83InstallsActorScopedLifecycleMarkerAndInvokerSafeTrigger()
            throws Exception {
        assertThat(strings(
                        """
                        SELECT relrowsecurity::text || ':' || relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid =
                            'public.calendar_adoption_source_lifecycle'::regclass
                        """))
                .containsExactly("true:true");
        assertThat(strings(
                        """
                        SELECT reloptions::text
                        FROM pg_class
                        WHERE oid =
                            'public.calendar_adoption_effective_state'::regclass
                        """))
                .singleElement()
                .satisfies(options ->
                        assertThat(options).contains("security_invoker=true"));
        assertThat(strings(
                        """
                        SELECT p.prosecdef::text || ':' || coalesce(
                            array_to_string(p.proconfig, '|'), '')
                        FROM pg_proc p
                        JOIN pg_namespace namespace ON namespace.oid = p.pronamespace
                        WHERE namespace.nspname = 'public'
                          AND p.proname =
                              'record_calendar_adoption_source_lifecycle'
                        """))
                .singleElement()
                .satisfies(configuration -> {
                    assertThat(configuration).startsWith("false:");
                    assertThat(configuration).contains("search_path=pg_catalog, public");
                });
        assertThat(strings(
                        """
                        SELECT (NOT EXISTS (
                            SELECT 1
                            FROM aclexplode(coalesce(
                                p.proacl,
                                acldefault('f', p.proowner))) privilege
                            WHERE privilege.grantee = 0
                              AND privilege.privilege_type = 'EXECUTE'))::text
                        FROM pg_proc p
                        JOIN pg_namespace namespace ON namespace.oid = p.pronamespace
                        WHERE namespace.nspname = 'public'
                          AND p.proname =
                              'record_calendar_adoption_source_lifecycle'
                        """))
                .containsExactly("true");
        assertThat(strings(
                        """
                        SELECT tgname || '=' || pg_get_triggerdef(oid)
                        FROM pg_trigger
                        WHERE tgrelid = 'public.calendar_plan'::regclass
                          AND tgname = 'trg_calendar_shared_adoption_lifecycle'
                          AND NOT tgisinternal
                        """))
                .singleElement()
                .satisfies(trigger -> assertThat(trigger)
                        .contains("AFTER UPDATE OF status")
                        .contains("record_calendar_adoption_source_lifecycle()"));
    }

    @Test
    void v83RemainsAppliedWhenLaterMigrationsExist() throws Exception {
        assertThat(strings(
                        """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success
                          AND version = '83'
                        """))
                .containsExactly("83");
    }

    private static List<String> strings(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            var result = new java.util.ArrayList<String>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return List.copyOf(result);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
