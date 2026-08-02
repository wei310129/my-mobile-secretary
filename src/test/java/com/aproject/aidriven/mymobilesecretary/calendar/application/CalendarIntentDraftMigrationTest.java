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
                .containsExactly("fk_calendar_intent_draft_plan");
        assertThat(jdbc.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'calendar_intent_draft'
                          AND column_name IN (
                              'recurrence_rule', 'recurrence_until',
                              'route_preflight_status', 'route_preflight_hash')
                        ORDER BY column_name
                        """,
                        String.class))
                .containsExactly(
                        "recurrence_rule",
                        "recurrence_until",
                        "route_preflight_hash",
                        "route_preflight_status");
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
                        FROM pg_constraint
                        WHERE conrelid = 'public.calendar_intent_draft'::regclass
                          AND conname = 'chk_calendar_intent_draft_recurrence'
                          AND contype = 'c'
                        """,
                        Integer.class))
                .isEqualTo(1);
    }
}
