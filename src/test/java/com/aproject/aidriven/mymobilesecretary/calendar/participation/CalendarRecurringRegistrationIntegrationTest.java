package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.FixedScenarioClockConfiguration;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarOccurrenceKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(FixedScenarioClockConfiguration.class)
class CalendarRecurringRegistrationIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private Clock clock;
    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarRecurringRegistrationService recurringRegistrations;

    @Test
    void seriesKeepsOneSeatWhileEachOccurrenceReleasesOnlyTheSkippedSeat() {
        Fixture seriesFixture = fixture();
        UUID seriesId = seedSeries(seriesFixture);
        configureBasePolicy(seriesFixture);
        configureRecurring(
                seriesFixture,
                seriesId,
                CalendarRecurringRegistrationScope.SERIES);
        grantWholePlanViewer(
                seriesFixture,
                seriesFixture.recipientContext(),
                "series-recipient-viewer");
        grantWholePlanViewer(
                seriesFixture,
                seriesFixture.peerContext(),
                "series-peer-viewer");
        CalendarOccurrenceKey first =
                CalendarOccurrenceKey.timed(
                        LocalDateTime.of(2026, 8, 1, 19, 0));
        CalendarOccurrenceKey second =
                CalendarOccurrenceKey.timed(
                        LocalDateTime.of(2026, 8, 8, 19, 0));

        assertThat(join(
                                seriesFixture,
                                seriesFixture.recipientContext(),
                                "series-join",
                                seriesId,
                                first)
                        .state())
                .isEqualTo(CalendarRegistrationState.COMMITTED);
        skip(
                seriesFixture,
                seriesFixture.recipientContext(),
                "series-skip",
                seriesId,
                first);
        assertThat(join(
                                seriesFixture,
                                seriesFixture.peerContext(),
                                "series-peer-join",
                                seriesId,
                                second)
                        .state())
                .isEqualTo(CalendarRegistrationState.WAITLISTED);

        Fixture eachFixture = fixture();
        UUID eachSeriesId = seedSeries(eachFixture);
        configureBasePolicy(eachFixture);
        configureRecurring(
                eachFixture,
                eachSeriesId,
                CalendarRecurringRegistrationScope.EACH_OCCURRENCE);
        grantWholePlanViewer(
                eachFixture,
                eachFixture.recipientContext(),
                "each-recipient-viewer");
        grantWholePlanViewer(
                eachFixture,
                eachFixture.peerContext(),
                "each-peer-viewer");

        assertThat(join(
                                eachFixture,
                                eachFixture.recipientContext(),
                                "each-join",
                                eachSeriesId,
                                first)
                        .state())
                .isEqualTo(CalendarRegistrationState.COMMITTED);
        skip(
                eachFixture,
                eachFixture.recipientContext(),
                "each-skip",
                eachSeriesId,
                first);
        assertThat(join(
                                eachFixture,
                                eachFixture.peerContext(),
                                "each-peer-join",
                                eachSeriesId,
                                first)
                        .state())
                .isEqualTo(CalendarRegistrationState.COMMITTED);
        assertThat(runtime(
                        eachFixture.recipientContext(),
                        () -> countActorRegistrations(eachSeriesId)))
                .isEqualTo(1L);
        assertThat(runtime(
                        eachFixture.ownerContext(),
                        () -> countActorRegistrations(eachSeriesId)))
                .isZero();
    }

    private void configureRecurring(
            Fixture fixture,
            UUID seriesId,
            CalendarRecurringRegistrationScope registrationScope) {
        inContext(
                fixture.ownerContext(),
                () -> {
                    recurringRegistrations.configure(
                            new CalendarRecurringRegistrationPolicyCommand(
                                    "recurring-policy-" + seriesId,
                                    fixture.planId(),
                                    planScope(fixture),
                                    seriesId,
                                    1,
                                    registrationScope));
                    return null;
                });
    }

    private CalendarRecurringRegistrationView join(
            Fixture fixture,
            com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext
                    actor,
            String requestId,
            UUID seriesId,
            CalendarOccurrenceKey occurrenceKey) {
        return runtime(
                actor,
                () -> recurringRegistrations.join(
                        new CalendarRecurringRegistrationJoinCommand(
                                requestId,
                                fixture.planId(),
                                planScope(fixture),
                                seriesId,
                                1,
                                occurrenceKey)));
    }

    private void skip(
            Fixture fixture,
            com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext
                    actor,
            String requestId,
            UUID seriesId,
            CalendarOccurrenceKey occurrenceKey) {
        runtime(
                actor,
                () -> recurringRegistrations.skipOccurrence(
                        requestId,
                        fixture.planId(),
                        planScope(fixture),
                        seriesId,
                        1,
                        occurrenceKey));
    }

    private void configureBasePolicy(Fixture fixture) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "base-policy-" + fixture.planId(),
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofDays(1)),
                                now.plus(Duration.ofDays(30)),
                                "Asia/Taipei",
                                1,
                                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                0)));
    }

    private UUID seedSeries(Fixture fixture) {
        UUID seriesId = UUID.randomUUID();
        inContext(
                fixture.ownerContext(),
                () -> {
                    jdbc.update(
                            """
                            INSERT INTO calendar_recurrence_series (
                                id, plan_id, lineage_root_id, active_revision,
                                created_at, updated_at, workspace_id,
                                source_created_by_user_id)
                            VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP,
                                CURRENT_TIMESTAMP, ?, ?)
                            """,
                            seriesId,
                            fixture.planId(),
                            seriesId,
                            fixture.ownerContext().workspaceId(),
                            fixture.ownerContext().actorId());
                    return jdbc.update(
                            """
                            INSERT INTO calendar_recurrence_rule_revision (
                                series_id, revision, frequency,
                                recurrence_interval, weekdays, week_start,
                                timed_anchor,
                                duration_seconds, zone_id, end_kind,
                                effective_from_timed, state,
                                request_hash, payload_hash,
                                created_by_actor_id, created_at,
                                workspace_id, source_created_by_user_id)
                            VALUES (?, 1, 'WEEKLY', 1,
                                ARRAY[6]::smallint[], 1, ?, 3600,
                                'Asia/Taipei', 'UNBOUNDED', ?, 'ACTIVE',
                                ?, ?, ?, CURRENT_TIMESTAMP, ?, ?)
                            """,
                            seriesId,
                            java.sql.Timestamp.valueOf(
                                    "2026-08-01 19:00:00"),
                            java.sql.Timestamp.valueOf(
                                    "2026-08-01 19:00:00"),
                            "a".repeat(64),
                            "b".repeat(64),
                            fixture.ownerContext().actorId(),
                            fixture.ownerContext().workspaceId(),
                            fixture.ownerContext().actorId());
                });
        return seriesId;
    }

    private long countActorRegistrations(UUID seriesId) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_recurring_registration
                WHERE series_id = ?
                """,
                Long.class,
                seriesId);
    }
}
