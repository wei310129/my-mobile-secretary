package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarRouteRiskMigrationTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void routeRiskHasTypedNodeForeignKeysAndForcedActorRls() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT relrowsecurity::text || ':' ||
                               relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid = 'public.calendar_route_risk'::regclass
                        """,
                        String.class))
                .isEqualTo("true:true");
        assertThat(jdbc.queryForList(
                        """
                        SELECT policyname || '=' || cmd
                        FROM pg_policies
                        WHERE schemaname = 'public'
                          AND tablename = 'calendar_route_risk'
                        ORDER BY policyname
                        """,
                        String.class))
                .containsExactly("rls_calendar_route_risk_actor=ALL");
        assertThat(jdbc.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'public.calendar_route_risk'::regclass
                          AND contype = 'f'
                        ORDER BY conname
                        """,
                        String.class))
                .containsExactly(
                        "fk_calendar_route_risk_from_node",
                        "fk_calendar_route_risk_to_node");
        assertThat(jdbc.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'calendar_route_risk'
                          AND is_nullable = 'NO'
                          AND column_name IN (
                              'from_plan_id', 'from_node_id',
                              'from_source_created_by_user_id',
                              'to_plan_id', 'to_node_id',
                              'to_source_created_by_user_id',
                              'workspace_id', 'created_by_user_id')
                        ORDER BY column_name
                        """,
                        String.class))
                .containsExactlyInAnyOrderElementsOf(List.of(
                        "from_plan_id",
                        "from_node_id",
                        "from_source_created_by_user_id",
                        "to_plan_id",
                        "to_node_id",
                        "to_source_created_by_user_id",
                        "workspace_id",
                        "created_by_user_id"));
    }
}
