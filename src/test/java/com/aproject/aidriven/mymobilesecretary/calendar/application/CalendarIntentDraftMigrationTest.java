package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarIntentDraftMigrationTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void draftTableHasForcedActorRlsAndTypedCalendarIdentity() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT relrowsecurity::text || ':' || relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid = 'public.calendar_intent_draft'::regclass
                        """,
                        String.class))
                .isEqualTo("true:true");
        assertThat(jdbc.queryForList(
                        """
                        SELECT policyname || '=' || cmd
                        FROM pg_policies
                        WHERE schemaname = 'public'
                          AND tablename = 'calendar_intent_draft'
                        ORDER BY policyname
                        """,
                        String.class))
                .containsExactly("rls_calendar_intent_draft_actor=ALL");
        assertThat(jdbc.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'public.calendar_intent_draft'::regclass
                          AND contype = 'f'
                        ORDER BY conname
                        """,
                        String.class))
                .containsExactly(
                        "fk_calendar_intent_draft_plan",
                        "fk_calendar_intent_draft_transport_node");
        assertThat(jdbc.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'calendar_intent_draft'
                          AND column_name IN (
                              'recurrence_rule', 'recurrence_until',
                              'route_preflight_status', 'route_preflight_hash',
                              'location_source', 'transport_origin_source')
                        ORDER BY column_name
                        """,
                        String.class))
                .containsExactly(
                        "location_source",
                        "recurrence_rule",
                        "recurrence_until",
                        "route_preflight_hash",
                        "route_preflight_status",
                        "transport_origin_source");
        assertThat(jdbc.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'public.calendar_intent_draft'::regclass
                          AND conname IN (
                              'chk_calendar_intent_draft_location_source',
                              'chk_calendar_intent_draft_transport_origin_source')
                        ORDER BY conname
                        """,
                        String.class))
                .containsExactly(
                        "chk_calendar_intent_draft_location_source",
                        "chk_calendar_intent_draft_transport_origin_source");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM pg_constraint
                        WHERE conrelid = 'public.calendar_intent_draft'::regclass
                          AND conname = 'chk_calendar_intent_draft_route_preflight'
                          AND contype = 'c'
                        """,
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'calendar_intent_draft'
                          AND column_name = 'route_journey_kind'
                        """,
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM pg_constraint
                        WHERE conrelid = 'public.calendar_intent_draft'::regclass
                          AND conname = 'chk_calendar_intent_draft_route_journey_kind'
                          AND contype = 'c'
                        """,
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM pg_constraint
                        WHERE conrelid = 'public.calendar_intent_draft'::regclass
                          AND conname = 'chk_calendar_intent_draft_recurrence'
                          AND contype = 'c'
                        """,
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT relrowsecurity::text || ':' || relforcerowsecurity::text
                        FROM pg_class
                        WHERE oid = 'public.actor_route_operation_preference'::regclass
                        """,
                        String.class))
                .isEqualTo("true:true");
        assertThat(jdbc.queryForList(
                        """
                        SELECT policyname || '=' || cmd
                        FROM pg_policies
                        WHERE schemaname = 'public'
                          AND tablename = 'actor_route_operation_preference'
                        ORDER BY policyname
                        """,
                        String.class))
                .containsExactly("rls_actor_route_operation_preference_actor=ALL");
        assertThat(jdbc.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'actor_route_operation_preference'
                          AND column_name IN (
                              'general_before_minutes', 'general_after_minutes',
                              'parking_minutes', 'ride_hail_wait_minutes')
                        ORDER BY column_name
                        """,
                        String.class))
                .containsExactly(
                        "general_after_minutes",
                        "general_before_minutes",
                        "parking_minutes",
                        "ride_hail_wait_minutes");
    }
}
