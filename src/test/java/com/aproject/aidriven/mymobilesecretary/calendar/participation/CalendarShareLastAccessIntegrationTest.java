package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareLastAccessResolution;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarShareLastAccessIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarRegistrationService registrations;
    @Autowired private Clock clock;

    @Test
    void preservingLastAccessCreatesMinimumAccessWithoutChangingParticipation() {
        Fixture fixture = fixture();
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "minimum-viewer",
                        fixture.planId(),
                        fixture.recipientContext().actorId(),
                        1));
        configurePolicy(fixture, "minimum-policy");
        CalendarRegistrationView registration = runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "minimum-registration",
                        fixture.planId(),
                        planScope(fixture))));

        inContext(
                fixture.ownerContext(),
                () -> shares.revoke(
                        "minimum-revoke",
                        share.id(),
                        share.revision(),
                        CalendarShareLastAccessResolution.preserve()));

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_participant_minimum_access
                        WHERE registration_id = ?
                          AND access_status = 'ACTIVE'
                        """,
                        Long.class,
                        registration.id()))
                .isOne();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT registration_state
                        FROM calendar_registration
                        WHERE id = ?
                        """,
                        String.class,
                        registration.id()))
                .isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT participation_state
                        FROM calendar_participation
                        WHERE plan_id = ? AND created_by_user_id = ?
                        """,
                        String.class,
                        fixture.planId(),
                        fixture.recipientContext().actorId()))
                .isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_outbox
                        WHERE registration_id = ?
                          AND event_type =
                            'PARTICIPANT_MINIMUM_ACCESS'
                        """,
                        Long.class,
                        registration.id()))
                .isOne();
    }

    @Test
    void removalResolutionExitsParticipantBeforeRevokingLastAccess() {
        Fixture fixture = fixture();
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "removal-viewer",
                        fixture.planId(),
                        fixture.recipientContext().actorId(),
                        1));
        configurePolicy(fixture, "removal-policy");
        CalendarRegistrationView registration = runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "removal-registration",
                        fixture.planId(),
                        planScope(fixture))));

        inContext(
                fixture.ownerContext(),
                () -> shares.revoke(
                        "removal-revoke",
                        share.id(),
                        share.revision(),
                        CalendarShareLastAccessResolution.removeParticipant(
                                registration.id(),
                                registration.revision(),
                                "access revoked by organizer")));

        assertThat(jdbc.queryForObject(
                        """
                        SELECT registration_state
                        FROM calendar_registration
                        WHERE id = ?
                        """,
                        String.class,
                        registration.id()))
                .isEqualTo("REMOVED_BY_ORGANIZER");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_participant_minimum_access
                        WHERE registration_id = ?
                        """,
                        Long.class,
                        registration.id()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_outbox
                        WHERE registration_id = ?
                          AND event_type =
                            'REMOVED_BY_ORGANIZER'
                        """,
                        Long.class,
                        registration.id()))
                .isOne();
    }

    private void configurePolicy(Fixture fixture, String requestId) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                requestId,
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                now.plus(Duration.ofHours(2)),
                                "Asia/Taipei",
                                4,
                                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                0)));
    }
}
