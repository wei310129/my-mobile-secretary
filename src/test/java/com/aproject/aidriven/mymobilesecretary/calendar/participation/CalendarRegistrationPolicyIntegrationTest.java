package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarRegistrationPolicyIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarOrganizerAssignmentService organizers;
    @Autowired private CalendarRosterService rosters;
    @Autowired private Clock clock;

    @Test
    void effectiveOwnerConfiguresPolicyAndPlainAdminCannot() {
        Fixture fixture = fixture();
        Instant now = Instant.now(clock);

        CalendarRegistrationPolicyView configured = inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "configure-registration",
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                now.plus(Duration.ofHours(2)),
                                "Asia/Taipei",
                                3,
                                CalendarLateJoinPolicy.WAITLIST_ONLY,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                true,
                                0)));

        assertThat(configured.capacity()).isEqualTo(3);
        assertThat(configured.revision()).isEqualTo(1);
        assertThat(configured.capacityState())
                .isEqualTo(CalendarCapacityState.WITHIN_CAPACITY);
        assertThatThrownBy(() -> inContext(
                        fixture.adminContext(),
                        () -> registrationPolicies.configure(
                                new CalendarRegistrationPolicyChange(
                                        "admin-policy",
                                        fixture.planId(),
                                        planScope(fixture),
                                        now,
                                        now.plus(Duration.ofHours(1)),
                                        "Asia/Taipei",
                                        1,
                                        CalendarLateJoinPolicy.CLOSED,
                                        false,
                                        CalendarWaitlistPromotionMode.MANUAL_OFFER,
                                        CalendarRegistrationLimitMode.HARD_LIMIT,
                                        Duration.ofMinutes(10),
                                        CalendarLateNotificationPolicy.OFF,
                                        false,
                                        1))))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void scopedManagerCanReadRosterButViewerAdminAndPeerCannot() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "registration-viewer");
        configureOpenPolicy(fixture);

        CalendarOrganizerAssignmentView assignment = inContext(
                fixture.ownerContext(),
                () -> organizers.change(new CalendarOrganizerAssignmentChange(
                        "assign-manager",
                        fixture.planId(),
                        planScope(fixture),
                        fixture.peerContext().actorId(),
                        true,
                        true,
                        CalendarOrganizerAssignmentAction.ASSIGN,
                        0)));

        assertThat(assignment.status())
                .isEqualTo(CalendarOrganizerAssignmentStatus.ACTIVE);
        assertThat(inContext(
                                fixture.peerContext(),
                                () -> rosters.managerRoster(
                                        fixture.planId(),
                                        planScope(fixture)))
                        .entries())
                .isEmpty();
        assertThatThrownBy(() -> inContext(
                        fixture.recipientContext(),
                        () -> rosters.managerRoster(
                                fixture.planId(),
                                planScope(fixture))))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> inContext(
                        fixture.adminContext(),
                        () -> rosters.managerRoster(
                                fixture.planId(),
                                planScope(fixture))))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void managerRlsSeesOnlyAssignedTargetAndPrivateFieldsStayOutOfView() {
        Fixture fixture = fixture();
        configureOpenPolicy(fixture);
        inContext(
                fixture.ownerContext(),
                () -> organizers.change(new CalendarOrganizerAssignmentChange(
                        "assign-rls-manager",
                        fixture.planId(),
                        planScope(fixture),
                        fixture.peerContext().actorId(),
                        true,
                        false,
                        CalendarOrganizerAssignmentAction.ASSIGN,
                        0)));

        assertThat(runtimeCount(
                        fixture.peerContext(),
                        "calendar_registration_policy",
                        fixture.planId()))
                .isEqualTo(1);
        assertThat(runtimeCount(
                        fixture.adminContext(),
                        "calendar_registration_policy",
                        fixture.planId()))
                .isZero();
        assertThat(CalendarRosterEntry.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("displayName", "status");
    }

    private void configureOpenPolicy(Fixture fixture) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "configure-" + fixture.planId(),
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                now.plus(Duration.ofHours(2)),
                                "Asia/Taipei",
                                2,
                                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                true,
                                0)));
    }
}
