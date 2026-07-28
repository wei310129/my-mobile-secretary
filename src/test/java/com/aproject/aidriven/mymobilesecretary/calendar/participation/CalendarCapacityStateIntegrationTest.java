package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarCapacityStateIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationAccess registrationAccess;
    @Autowired private CalendarParticipationAccess participationAccess;
    @Autowired private CalendarParticipationService participations;
    @Autowired private CalendarRegistrationService registrations;
    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarOrganizerAssignmentService organizers;
    @Autowired private Clock clock;

    @Test
    void capacityTransitionsAreAuditedAndNeverRemoveRegistrations() {
        Fixture fixture = fixture();
        WorkspaceContext third = addActor(fixture, "capacity third");
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "state-viewer-1");
        grantWholePlanViewer(
                fixture, fixture.peerContext(), "state-viewer-2");
        grantWholePlanViewer(fixture, third, "state-viewer-3");
        configure(
                fixture,
                3,
                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                Instant.now(clock).plus(Duration.ofHours(2)),
                0);
        join(fixture, fixture.recipientContext(), "state-join-1");
        join(fixture, fixture.peerContext(), "state-join-2");
        join(fixture, third, "state-join-3");
        inContext(
                fixture.ownerContext(),
                () -> organizers.change(
                        new CalendarOrganizerAssignmentChange(
                                "state-manager",
                                fixture.planId(),
                                planScope(fixture),
                                fixture.peerContext().actorId(),
                                true,
                                true,
                                CalendarOrganizerAssignmentAction.ASSIGN,
                                0)));
        List<RegistrationSnapshot> before = registrations(fixture);

        Instant downsizeClosesAt =
                Instant.now(clock).plus(Duration.ofHours(2));
        CalendarRegistrationPolicyView over = configure(
                fixture,
                2,
                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                downsizeClosesAt,
                1);
        CalendarRegistrationPolicyView replay = configure(
                fixture,
                2,
                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                downsizeClosesAt,
                1);

        assertThat(replay).isEqualTo(over);
        assertThat(over.capacityState())
                .isEqualTo(CalendarCapacityState.OVER_CAPACITY);
        assertThat(bucketCount(fixture, "committed_count"))
                .isEqualTo(3);
        assertThat(bucketCount(fixture, "over_capacity_count"))
                .isEqualTo(1);
        assertThat(registrations(fixture)).isEqualTo(before);
        assertThat(capacityHistoryStates(fixture))
                .containsExactly("WITHIN_CAPACITY->OVER_CAPACITY");
        assertThat(overCapacityRecipients(fixture))
                .containsExactlyInAnyOrder(
                        fixture.ownerContext().actorId(),
                        fixture.peerContext().actorId());

        CalendarRegistrationPolicyView within = configure(
                fixture,
                3,
                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                Instant.now(clock).plus(Duration.ofHours(2)),
                2);

        assertThat(within.capacityState())
                .isEqualTo(CalendarCapacityState.WITHIN_CAPACITY);
        assertThat(bucketCount(fixture, "over_capacity_count"))
                .isZero();
        assertThat(registrations(fixture)).isEqualTo(before);
        assertThat(capacityHistoryStates(fixture))
                .containsExactly(
                        "WITHIN_CAPACITY->OVER_CAPACITY",
                        "OVER_CAPACITY->WITHIN_CAPACITY");
        assertThat(overCapacityRecipients(fixture)).hasSize(2);
    }

    @Test
    void exactClosedDeadlineRejectsBeforeAnyMutation() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "closed-viewer");
        Instant closesAt =
                Instant.now(clock).plus(Duration.ofHours(1));
        CalendarRegistrationPolicyView closedPolicy = configure(
                fixture,
                2,
                CalendarLateJoinPolicy.CLOSED,
                closesAt,
                0);
        long participationBefore =
                tableCount(fixture, "calendar_participation");
        long bucketRevision = bucketLong(fixture, "bucket_revision");

        CalendarRegistrationService atDeadline =
                new CalendarRegistrationService(
                        registrationAccess,
                        participationAccess,
                        participations,
                        jdbc,
                        Clock.fixed(
                                closedPolicy.closesAt(),
                                ZoneOffset.UTC));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> atDeadline.join(
                                new CalendarRegistrationJoinCommand(
                                        "closed-at-deadline",
                                        fixture.planId(),
                                        planScope(fixture)))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo(
                                        "CALENDAR_REGISTRATION_CLOSED"));
        assertThat(tableCount(fixture, "calendar_registration"))
                .isZero();
        assertThat(tableCount(fixture, "calendar_participation"))
                .isEqualTo(participationBefore);
        assertThat(bucketCount(fixture, "committed_count"))
                .isZero();
        assertThat(bucketCount(fixture, "waitlisted_count"))
                .isZero();
        assertThat(bucketLong(fixture, "bucket_revision"))
                .isEqualTo(bucketRevision);
        assertThat(capacityHistoryStates(fixture)).isEmpty();
    }

    private CalendarRegistrationPolicyView configure(
            Fixture fixture,
            int capacity,
            CalendarLateJoinPolicy lateJoinPolicy,
            Instant closesAt,
            long expectedRevision) {
        return inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "capacity-state-"
                                        + fixture.planId()
                                        + "-"
                                        + expectedRevision,
                                fixture.planId(),
                                planScope(fixture),
                                closesAt.minus(Duration.ofHours(3)),
                                closesAt,
                                "Asia/Taipei",
                                capacity,
                                lateJoinPolicy,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                expectedRevision)));
    }

    private CalendarRegistrationView join(
            Fixture fixture,
            WorkspaceContext actor,
            String requestId) {
        return runtime(
                actor,
                () -> registrations.join(
                        new CalendarRegistrationJoinCommand(
                                requestId,
                                fixture.planId(),
                                planScope(fixture))));
    }

    private List<RegistrationSnapshot> registrations(Fixture fixture) {
        return jdbc.query(
                """
                SELECT id, registration_state, registration_revision
                FROM calendar_registration
                WHERE plan_id = ?
                ORDER BY id
                """,
                (row, ignored) -> new RegistrationSnapshot(
                        row.getObject("id", java.util.UUID.class),
                        row.getString("registration_state"),
                        row.getLong("registration_revision")),
                fixture.planId());
    }

    private List<String> capacityHistoryStates(Fixture fixture) {
        return jdbc.queryForList(
                """
                SELECT previous_state || '->' || current_state
                FROM calendar_capacity_history
                WHERE plan_id = ?
                ORDER BY policy_revision
                """,
                String.class,
                fixture.planId());
    }

    private List<java.util.UUID> overCapacityRecipients(
            Fixture fixture) {
        return jdbc.queryForList(
                """
                SELECT recipient_user_id
                FROM calendar_registration_outbox
                WHERE plan_id = ?
                  AND event_type = 'CAPACITY_OVER_CAPACITY'
                ORDER BY recipient_user_id
                """,
                java.util.UUID.class,
                fixture.planId());
    }

    private int bucketCount(Fixture fixture, String column) {
        return jdbc.queryForObject(
                "SELECT "
                        + column
                        + " FROM calendar_capacity_bucket"
                        + " WHERE plan_id = ?",
                Integer.class,
                fixture.planId());
    }

    private long bucketLong(Fixture fixture, String column) {
        return jdbc.queryForObject(
                "SELECT "
                        + column
                        + " FROM calendar_capacity_bucket"
                        + " WHERE plan_id = ?",
                Long.class,
                fixture.planId());
    }

    private long tableCount(Fixture fixture, String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM "
                        + table
                        + " WHERE plan_id = ?",
                Long.class,
                fixture.planId());
    }

    private record RegistrationSnapshot(
            java.util.UUID id,
            String state,
            long revision) {}
}
