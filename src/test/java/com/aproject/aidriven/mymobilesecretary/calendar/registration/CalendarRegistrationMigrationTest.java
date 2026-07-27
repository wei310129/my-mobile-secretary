package com.aproject.aidriven.mymobilesecretary.calendar.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarRegistrationMigrationTest extends IntegrationTestBase {

    private static final List<String> V88_TABLES = List.of(
            "calendar_plan_ownership",
            "calendar_ownership_transfer",
            "calendar_active_owner_membership_guard",
            "calendar_pending_transfer_membership_guard",
            "calendar_organizer_assignment",
            "calendar_registration_policy",
            "calendar_capacity_bucket",
            "calendar_registration",
            "calendar_registration_history",
            "calendar_waitlist_entry",
            "calendar_waitlist_offer",
            "calendar_participant_minimum_access",
            "calendar_registration_request_receipt",
            "calendar_registration_outbox");

    @Autowired private JdbcTemplate jdbc;

    @Test
    void v88IsAppliedAndRemainsForwardCompatibleWithLaterMigrations() {
        Integer applied = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM flyway_schema_history
                WHERE version = '88'
                  AND script =
                    'V88__create_calendar_registration_and_ownership.sql'
                  AND success
                """,
                Integer.class);
        Integer latest = jdbc.queryForObject(
                """
                SELECT max(version::integer)
                FROM flyway_schema_history
                WHERE success AND version ~ '^[0-9]+$'
                """,
                Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(latest).isGreaterThanOrEqualTo(88);
    }

    @Test
    void allRegistrationAndOwnershipTablesForceRowLevelSecurity() {
        List<String> protectedTables = jdbc.queryForList(
                """
                SELECT relname
                FROM pg_class
                WHERE relname IN (
                  'calendar_plan_ownership',
                  'calendar_ownership_transfer',
                  'calendar_active_owner_membership_guard',
                  'calendar_pending_transfer_membership_guard',
                  'calendar_organizer_assignment',
                  'calendar_registration_policy',
                  'calendar_capacity_bucket',
                  'calendar_registration',
                  'calendar_registration_history',
                  'calendar_waitlist_entry',
                  'calendar_waitlist_offer',
                  'calendar_participant_minimum_access',
                  'calendar_registration_request_receipt',
                  'calendar_registration_outbox')
                  AND relrowsecurity
                  AND relforcerowsecurity
                ORDER BY relname
                """,
                String.class);

        assertThat(protectedTables)
                .containsExactlyInAnyOrderElementsOf(V88_TABLES);
    }

    @Test
    void migrationInstallsConcurrencyAndAppendOnlyGuards() {
        List<String> indexes = jdbc.queryForList(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE indexname IN (
                    'uq_calendar_ownership_transfer_offered',
                    'uq_calendar_registration_actor_target',
                    'uq_calendar_waitlist_target_sequence',
                    'uq_calendar_waitlist_offer_active_target')
                """,
                String.class);
        List<String> triggers = jdbc.queryForList(
                """
                SELECT DISTINCT trigger_name
                FROM information_schema.triggers
                WHERE trigger_name IN (
                    'trg_calendar_registration_history_append_only',
                    'trg_calendar_registration_outbox_no_delete',
                    'trg_calendar_plan_ownership_source_immutable',
                    'trg_calendar_ownership_transfer_transition',
                    'trg_calendar_ownership_requires_accepted_transfer',
                    'trg_calendar_transfer_requires_ownership_change',
                    'trg_calendar_active_owner_membership_guard',
                    'trg_calendar_pending_transfer_membership_guard',
                    'trg_calendar_refresh_active_owner_membership',
                    'trg_calendar_refresh_plan_membership_guard',
                    'trg_calendar_create_pending_transfer_membership',
                    'trg_calendar_close_pending_transfer_membership')
                """,
                String.class);

        assertThat(indexes).hasSize(4);
        assertThat(triggers).hasSize(12);
    }
}
