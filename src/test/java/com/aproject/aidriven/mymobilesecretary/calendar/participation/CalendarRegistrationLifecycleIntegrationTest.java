package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarRegistrationLifecycleIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarOrganizerAssignmentService organizers;
    @Autowired private CalendarRegistrationService registrations;
    @Autowired private CalendarRegistrationLifecycleService lifecycle;
    @Autowired private Clock clock;

    @Test
    void userWithdrawalIsAtomicPrivateAndIdempotent() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "withdraw-viewer");
        configurePolicy(fixture, "withdraw-policy", null, 0);
        assignNotificationManager(fixture);
        CalendarRegistrationView joined = runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "withdraw-join",
                        fixture.planId(),
                        planScope(fixture))));
        configurePolicy(
                fixture,
                "withdraw-policy-closed",
                Instant.now(clock).minus(Duration.ofMinutes(1)),
                1);

        CalendarRegistrationView withdrawn = runtime(
                fixture.recipientContext(),
                () -> lifecycle.withdraw(new CalendarRegistrationWithdrawalCommand(
                        "withdraw-registration",
                        fixture.planId(),
                        planScope(fixture),
                        joined.revision(),
                        "private medical reason",
                        false)));
        CalendarRegistrationView replay = runtime(
                fixture.recipientContext(),
                () -> lifecycle.withdraw(new CalendarRegistrationWithdrawalCommand(
                        "withdraw-registration",
                        fixture.planId(),
                        planScope(fixture),
                        joined.revision(),
                        "private medical reason",
                        false)));

        assertThat(withdrawn.state())
                .isEqualTo(CalendarRegistrationState.WITHDRAWN_BY_USER);
        assertThat(replay).isEqualTo(withdrawn);
        assertThat(bucketCount(fixture, "committed_count")).isZero();
        assertThat(participationState(fixture, fixture.recipientContext()))
                .isEqualTo("OPTED_OUT");
        assertThat(outboxCount(fixture, "WITHDRAWN_BY_USER")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_outbox
                        WHERE plan_id = ? AND payload_text LIKE ?
                        """,
                        Long.class,
                        fixture.planId(),
                        "%private medical reason%"))
                .isZero();
    }

    @Test
    void scopedManagerRemovalIsDistinctAndRequiresActorNotification() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "removal-viewer");
        configurePolicy(fixture, "removal-policy", null, 0);
        assignNotificationManager(fixture);
        CalendarRegistrationView joined = runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "removal-join",
                        fixture.planId(),
                        planScope(fixture))));
        configurePolicy(
                fixture,
                "removal-policy-closed",
                Instant.now(clock).minus(Duration.ofMinutes(1)),
                1);

        CalendarRegistrationView removed = runtime(
                fixture.peerContext(),
                () -> lifecycle.remove(new CalendarOrganizerRemovalCommand(
                        "remove-registration",
                        fixture.planId(),
                        planScope(fixture),
                        fixture.recipientContext().actorId(),
                        joined.revision(),
                        "duplicate registration")));

        assertThat(removed.state())
                .isEqualTo(CalendarRegistrationState.REMOVED_BY_ORGANIZER);
        assertThat(bucketCount(fixture, "committed_count")).isZero();
        assertThat(participationState(fixture, fixture.recipientContext()))
                .isEqualTo("OPTED_OUT");
        assertThat(outboxCount(fixture, "REMOVED_BY_ORGANIZER")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_history
                        WHERE plan_id = ?
                          AND event_type = 'REMOVED_BY_ORGANIZER'
                          AND status_origin = 'MANAGER'
                        """,
                        Long.class,
                        fixture.planId()))
                .isOne();
    }

    @Test
    void waitlistedWithdrawalClosesQueueAndNeverPromotesAutomatically() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "queue-viewer-a");
        grantWholePlanViewer(
                fixture, fixture.peerContext(), "queue-viewer-b");
        configurePolicy(fixture, "queue-policy", null, 0);
        runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "queue-join-committed",
                        fixture.planId(),
                        planScope(fixture))));
        CalendarRegistrationView waiting = runtime(
                fixture.peerContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "queue-join-waiting",
                        fixture.planId(),
                        planScope(fixture))));

        runtime(
                fixture.peerContext(),
                () -> lifecycle.withdraw(new CalendarRegistrationWithdrawalCommand(
                        "queue-withdraw",
                        fixture.planId(),
                        planScope(fixture),
                        waiting.revision(),
                        null,
                        false)));

        assertThat(bucketCount(fixture, "committed_count")).isEqualTo(1);
        assertThat(bucketCount(fixture, "waitlisted_count")).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT entry_state
                        FROM calendar_waitlist_entry
                        WHERE plan_id = ? AND created_by_user_id = ?
                        """,
                        String.class,
                        fixture.planId(),
                        fixture.peerContext().actorId()))
                .isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration
                        WHERE plan_id = ?
                          AND registration_state = 'COMMITTED'
                        """,
                        Long.class,
                        fixture.planId()))
                .isOne();
    }

    @Test
    void viewerWithoutManagerAssignmentCannotRemoveParticipant() {
        Fixture fixture = fixture();
        var unauthorized = addActor(fixture, "unauthorized-remover");
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "secure-viewer");
        grantWholePlanViewer(
                fixture, unauthorized, "unauthorized-viewer");
        configurePolicy(fixture, "secure-removal-policy", null, 0);
        CalendarRegistrationView joined = runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "secure-removal-join",
                        fixture.planId(),
                        planScope(fixture))));

        assertThatThrownBy(() -> runtime(
                        unauthorized,
                        () -> lifecycle.remove(new CalendarOrganizerRemovalCommand(
                                "unauthorized-removal",
                                fixture.planId(),
                                planScope(fixture),
                                fixture.recipientContext().actorId(),
                                joined.revision(),
                                "not authorized"))))
                .isInstanceOf(SecurityException.class);

        assertThat(runtime(
                                fixture.recipientContext(),
                                () -> registrations.current(
                                        fixture.planId(),
                                        planScope(fixture)))
                        .orElseThrow()
                        .state())
                .isEqualTo(CalendarRegistrationState.COMMITTED);
        assertThat(bucketCount(fixture, "committed_count")).isOne();
        assertThat(outboxCount(fixture, "REMOVED_BY_ORGANIZER")).isZero();
    }

    private void configurePolicy(
            Fixture fixture,
            String requestId,
            Instant closesAt,
            long expectedRevision) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                requestId,
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                closesAt == null
                                        ? now.plus(Duration.ofHours(2))
                                        : closesAt,
                                "Asia/Taipei",
                                1,
                                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                expectedRevision)));
    }

    private void assignNotificationManager(Fixture fixture) {
        inContext(
                fixture.ownerContext(),
                () -> organizers.change(new CalendarOrganizerAssignmentChange(
                        "assign-lifecycle-manager",
                        fixture.planId(),
                        planScope(fixture),
                        fixture.peerContext().actorId(),
                        true,
                        true,
                        CalendarOrganizerAssignmentAction.ASSIGN,
                        0)));
    }

    private int bucketCount(Fixture fixture, String column) {
        return jdbc.queryForObject(
                "SELECT "
                        + column
                        + " FROM calendar_capacity_bucket WHERE plan_id = ?",
                Integer.class,
                fixture.planId());
    }

    private String participationState(
            Fixture fixture,
            com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext actor) {
        return jdbc.queryForObject(
                """
                SELECT participation_state
                FROM calendar_participation
                WHERE plan_id = ? AND created_by_user_id = ?
                """,
                String.class,
                fixture.planId(),
                actor.actorId());
    }

    private long outboxCount(Fixture fixture, String eventType) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_outbox
                WHERE plan_id = ? AND event_type = ?
                """,
                Long.class,
                fixture.planId(),
                eventType);
    }
}
