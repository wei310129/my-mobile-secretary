package com.aproject.aidriven.mymobilesecretary.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Tag("migration")
class CalendarMigrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("calendar_migration");

    @Test
    void v66ThroughV75MigrateExistingSchemaWithoutChangingLegacyScheduleTable()
            throws Exception {
        migrateTo("65");
        List<String> before = columns("schedule_item");

        migrateTo("66");

        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("calendar_plan")).isTrue();
        assertThat(tableExists("calendar_activity")).isTrue();
        assertThat(tableExists("calendar_time_node")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename IN (
                            'calendar_plan', 'calendar_activity', 'calendar_time_node')
                        """))
                .isEqualTo(3);

        migrateTo("67");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("calendar_online_access_link")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'calendar_online_access_link'
                        """))
                .isEqualTo(1);

        execute(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (
                    '70000000-0000-0000-0000-000000000001',
                    'migration actor', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (
                    '70000000-0000-0000-0000-000000000002',
                    'migration workspace', 'PERSONAL',
                    '70000000-0000-0000-0000-000000000001',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
                INSERT INTO calendar_plan (
                    id, title, placement_kind, timed_start, zone_id,
                    version, created_at, updated_at, workspace_id,
                    created_by_user_id)
                VALUES (
                    '70000000-0000-0000-0000-000000000003',
                    'migration plan', 'TIMED_POINT',
                    '2026-07-23T12:00:00Z', 'Asia/Taipei',
                    0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    '70000000-0000-0000-0000-000000000002',
                    '70000000-0000-0000-0000-000000000001');
                INSERT INTO calendar_time_node (
                    id, plan_id, node_key, label, expression_kind,
                    absolute_time, offset_seconds, base_node_key,
                    criticality, adjustability, version,
                    revision, created_at, updated_at, workspace_id,
                    created_by_user_id)
                VALUES
                    (
                        '70000000-0000-0000-0000-000000000004',
                        '70000000-0000-0000-0000-000000000003',
                        'absolute', 'absolute', 'ABSOLUTE',
                        '2026-07-23T12:00:00Z', NULL, NULL,
                        'NORMAL', 'LOCKED', 0, 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        '70000000-0000-0000-0000-000000000002',
                        '70000000-0000-0000-0000-000000000001'),
                    (
                        '70000000-0000-0000-0000-000000000005',
                        '70000000-0000-0000-0000-000000000003',
                        'owner-relative', 'owner-relative',
                        'OWNER_START_OFFSET', NULL, -600, NULL,
                        'NORMAL', 'FLEXIBLE',
                        0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        '70000000-0000-0000-0000-000000000002',
                        '70000000-0000-0000-0000-000000000001'),
                    (
                        '70000000-0000-0000-0000-000000000006',
                        '70000000-0000-0000-0000-000000000003',
                        'node-relative', 'node-relative', 'NODE_OFFSET',
                        NULL, -900, 'absolute', 'NORMAL', 'FLEXIBLE', 0, 1,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        '70000000-0000-0000-0000-000000000002',
                        '70000000-0000-0000-0000-000000000001');
                """);
        migrateTo("68");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(columns("calendar_time_node"))
                .anyMatch(column -> column.startsWith("resolved_time:"));
        assertThat(tableExists("calendar_reminder_rule")).isTrue();
        assertThat(tableExists("calendar_reminder_occurrence")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename IN (
                            'calendar_reminder_rule',
                            'calendar_reminder_occurrence')
                        """))
                .isEqualTo(2);

        migrateTo("70");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("calendar_task_binding")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'calendar_task_binding'
                        """))
                .isEqualTo(1);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_task_binding_task',
                            'fk_calendar_task_binding_plan',
                            'fk_calendar_task_binding_activity',
                            'fk_calendar_task_binding_node',
                            'chk_calendar_task_binding_target')
                        """))
                .isEqualTo(5);

        migrateTo("71");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("task_reminder_rule")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'task_reminder_rule'
                        """))
                .isEqualTo(1);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'fk_task_reminder_rule_task',
                            'chk_task_reminder_rule_status',
                            'chk_task_reminder_rule_revision')
                        """))
                .isEqualTo(3);

        migrateTo("72");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("calendar_knowledge_fact_binding")).isTrue();
        assertThat(tableExists("calendar_knowledge_annotation_binding")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename IN (
                            'calendar_knowledge_fact_binding',
                            'calendar_knowledge_annotation_binding')
                        """))
                .isEqualTo(2);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_knowledge_fact_source',
                            'fk_calendar_knowledge_fact_plan',
                            'fk_calendar_knowledge_fact_activity',
                            'fk_calendar_knowledge_fact_node',
                            'chk_calendar_knowledge_fact_target',
                            'fk_calendar_knowledge_annotation_source',
                            'fk_calendar_knowledge_annotation_plan',
                            'fk_calendar_knowledge_annotation_activity',
                            'fk_calendar_knowledge_annotation_node',
                            'chk_calendar_knowledge_annotation_target')
                        """))
                .isEqualTo(10);

        migrateTo("73");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("calendar_attachment_binding")).isTrue();
        assertThat(tableExists("calendar_share_content_grant")).isFalse();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'calendar_attachment_binding'
                        """))
                .isEqualTo(1);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_attachment_media',
                            'fk_calendar_attachment_plan',
                            'fk_calendar_attachment_activity',
                            'fk_calendar_attachment_node',
                            'chk_calendar_attachment_target')
                        """))
                .isEqualTo(5);
        migrateTo("74");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("calendar_knowledge_excerpt")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'calendar_knowledge_excerpt'
                        """))
                .isEqualTo(1);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_knowledge_excerpt_plan',
                            'fk_calendar_knowledge_excerpt_fact_binding',
                            'fk_calendar_knowledge_excerpt_annotation_binding',
                            'chk_calendar_knowledge_excerpt_source',
                            'chk_calendar_knowledge_excerpt_status')
                        """))
                .isEqualTo(5);

        migrateTo("75");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(tableExists("knowledge_materialization")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'knowledge_materialization'
                        """))
                .isEqualTo(1);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'uq_knowledge_materialization_owned_identity',
                            'uq_knowledge_materialization_proposal_request',
                            'fk_knowledge_materialization_fact_binding',
                            'fk_knowledge_materialization_annotation_binding',
                            'fk_knowledge_materialization_task_binding',
                            'fk_knowledge_materialization_node',
                            'fk_knowledge_materialization_preference',
                            'fk_knowledge_materialization_task_reminder',
                            'fk_knowledge_materialization_calendar_reminder',
                            'fk_knowledge_materialization_buffer_rule',
                            'chk_knowledge_materialization_source',
                            'chk_knowledge_materialization_status',
                            'chk_knowledge_materialization_target',
                            'chk_knowledge_materialization_revision',
                            'chk_knowledge_materialization_scope',
                            'chk_knowledge_materialization_resolution',
                            'chk_knowledge_materialization_result',
                            'chk_knowledge_materialization_time',
                            'chk_knowledge_materialization_hashes')
                        """))
                .isEqualTo(19);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conrelid = 'knowledge_materialization'::regclass
                          AND contype = 'f'
                        """))
                .isEqualTo(8);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                            'uq_calendar_task_binding_result_identity',
                            'uq_planning_preference_owned_identity',
                            'uq_task_reminder_rule_result_identity',
                            'uq_calendar_reminder_rule_result_identity',
                            'uq_place_owned_identity',
                            'uq_buffer_rule_result_identity',
                            'fk_buffer_rule_owned_place',
                            'chk_buffer_rule_explicit_policy')
                        """))
                .isEqualTo(8);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'buffer_rule'
                          AND policyname = 'rls_buffer_rule_actor'
                        """))
                .isEqualTo(1);
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename = 'buffer_rule'
                        """))
                .isEqualTo(1);
        assertThat(strings(
                        """
                        SELECT c.conname || '=' ||
                            string_agg(
                                local_column.attname || '->' || referenced_column.attname,
                                ',' ORDER BY key_column.ordinality)
                        FROM pg_constraint c
                        CROSS JOIN LATERAL unnest(c.conkey, c.confkey)
                            WITH ORDINALITY AS key_column(
                                local_attnum, referenced_attnum, ordinality)
                        JOIN pg_attribute local_column
                          ON local_column.attrelid = c.conrelid
                         AND local_column.attnum = key_column.local_attnum
                        JOIN pg_attribute referenced_column
                          ON referenced_column.attrelid = c.confrelid
                         AND referenced_column.attnum = key_column.referenced_attnum
                        WHERE c.conname IN (
                            'fk_knowledge_materialization_calendar_reminder',
                            'fk_knowledge_materialization_buffer_rule')
                        GROUP BY c.conname
                        ORDER BY c.conname
                        """))
                .containsExactly(
                        "fk_knowledge_materialization_buffer_rule="
                                + "buffer_rule_id->id,buffer_rule_place_id->place_id,"
                                + "workspace_id->workspace_id,"
                                + "created_by_user_id->created_by_user_id",
                        "fk_knowledge_materialization_calendar_reminder="
                                + "calendar_reminder_rule_id->id,node_id->node_id,"
                                + "plan_id->plan_id,"
                                + "workspace_id->workspace_id,"
                                + "created_by_user_id->created_by_user_id");
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_indexes
                        WHERE indexname =
                            'uq_knowledge_materialization_resolution_request'
                        """))
                .isEqualTo(1);
        assertThat(strings(
                        """
                        SELECT node_key || '=' ||
                            to_char(
                                resolved_time AT TIME ZONE 'UTC',
                                'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                        FROM calendar_time_node
                        WHERE node_key IN (
                            'absolute', 'owner-relative', 'node-relative')
                        ORDER BY node_key
                        """))
                .containsExactly(
                        "absolute=2026-07-23T12:00:00Z",
                        "node-relative=2026-07-23T11:45:00Z",
                        "owner-relative=2026-07-23T11:50:00Z");

        migrateTo("69");
        assertThat(columns("schedule_item")).isEqualTo(before);
        assertThat(columns("calendar_time_node"))
                .anyMatch(column -> column.startsWith("location_label:"));
        assertThat(tableExists("calendar_adoption")).isTrue();
        assertThat(tableExists("calendar_adoption_node")).isTrue();
        assertThat(integer(
                        """
                        SELECT count(*) FROM pg_policies
                        WHERE tablename IN (
                            'calendar_adoption', 'calendar_adoption_node')
                        """))
                .isEqualTo(2);
    }

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .placeholders(Map.of(
                        "conversation_scope_current_key_version",
                        "1",
                        "conversation_scope_hmac_key_base64",
                        "dGVzdC1jYWxlbmRhci1taWdyYXRpb24taG1hYy1rZXk="))
                .load()
                .migrate();
    }

    private static List<String> columns(String table) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement(
                        """
                        SELECT column_name || ':' || data_type || ':' || is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = ?
                        ORDER BY ordinal_position
                        """)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                var result = new java.util.ArrayList<String>();
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
                return List.copyOf(result);
            }
        }
    }

    private static boolean tableExists(String table) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, "public." + table);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static int integer(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<String> strings(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            var result = new java.util.ArrayList<String>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return java.util.Collections.unmodifiableList(result);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
