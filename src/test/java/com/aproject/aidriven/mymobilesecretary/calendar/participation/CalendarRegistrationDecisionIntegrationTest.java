package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarRegistrationDecisionIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarOrganizerAssignmentService organizers;
    @Autowired private CalendarRegistrationService registrations;
    @Autowired private CalendarRegistrationDecisionService decisions;
    @Autowired private Clock clock;

    @Test
    void scopedManagerApprovalIsAtomicRevisionBoundAndIdempotent() {
        PendingRegistration pending = pending(
                CalendarRegistrationLimitMode.HARD_LIMIT,
                false,
                "decision-approve");
        long resultOutboxBaseline = resultOutboxCount(pending.fixture());
        CalendarRegistrationDecisionCommand command =
                new CalendarRegistrationDecisionCommand(
                        "approve-registration",
                        pending.fixture().planId(),
                        planScope(pending.fixture()),
                        pending.registration().id(),
                        CalendarRegistrationDecisionCommand.Decision.APPROVE,
                        null,
                        pending.registration().revision(),
                        false);

        CalendarRegistrationDecisionView approved = runtime(
                pending.fixture().peerContext(),
                () -> decisions.decide(command));
        CalendarRegistrationDecisionView replay = runtime(
                pending.fixture().peerContext(),
                () -> decisions.decide(command));

        assertThat(approved.state())
                .isEqualTo(CalendarRegistrationState.COMMITTED);
        assertThat(approved.overrideConfirmationRequired()).isFalse();
        assertThat(replay).isEqualTo(approved);
        assertThat(bucketCount(pending.fixture(), "committed_count"))
                .isOne();
        assertThat(participationState(pending.fixture())).isEqualTo("COMMITTED");
        assertThat(decisionHistoryCount(
                        pending.fixture(), "APPROVAL_APPROVED"))
                .isOne();
        assertThat(decisionReceiptCount(pending.fixture())).isOne();
        assertThat(resultOutboxCount(pending.fixture()))
                .isEqualTo(resultOutboxBaseline + 1);
    }

    @Test
    void scopedManagerRejectionDeclinesWithoutConsumingCapacity() {
        PendingRegistration pending = pending(
                CalendarRegistrationLimitMode.HARD_LIMIT,
                false,
                "decision-reject");
        long resultOutboxBaseline = resultOutboxCount(pending.fixture());
        CalendarRegistrationDecisionCommand command =
                new CalendarRegistrationDecisionCommand(
                        "reject-registration",
                        pending.fixture().planId(),
                        planScope(pending.fixture()),
                        pending.registration().id(),
                        CalendarRegistrationDecisionCommand.Decision.REJECT,
                        "Does not meet the participation requirements",
                        pending.registration().revision(),
                        false);

        CalendarRegistrationDecisionView rejected = runtime(
                pending.fixture().peerContext(),
                () -> decisions.decide(command));
        CalendarRegistrationDecisionView replay = runtime(
                pending.fixture().peerContext(),
                () -> decisions.decide(command));

        assertThat(rejected.state())
                .isEqualTo(CalendarRegistrationState.DECLINED);
        assertThat(replay).isEqualTo(rejected);
        assertThat(bucketCount(pending.fixture(), "committed_count"))
                .isZero();
        assertThat(participationState(pending.fixture())).isEqualTo("DECLINED");
        assertThat(decisionHistoryCount(
                        pending.fixture(), "APPROVAL_DECLINED"))
                .isOne();
        assertThat(decisionReceiptCount(pending.fixture())).isOne();
        assertThat(resultOutboxCount(pending.fixture()))
                .isEqualTo(resultOutboxBaseline + 1);
    }

    @Test
    void viewerWithoutScopedManagerAssignmentCannotDecide() {
        PendingRegistration pending = pending(
                CalendarRegistrationLimitMode.HARD_LIMIT,
                false,
                "decision-unauthorized");
        var unauthorized =
                addActor(pending.fixture(), "decision unauthorized");
        grantWholePlanViewer(
                pending.fixture(),
                unauthorized,
                "decision-unauthorized-viewer");
        long resultOutboxBaseline = resultOutboxCount(pending.fixture());

        assertThatThrownBy(() -> runtime(
                        unauthorized,
                        () -> decisions.decide(
                                new CalendarRegistrationDecisionCommand(
                                        "unauthorized-decision",
                                        pending.fixture().planId(),
                                        planScope(pending.fixture()),
                                        pending.registration().id(),
                                        CalendarRegistrationDecisionCommand
                                                .Decision.APPROVE,
                                        null,
                                        pending.registration().revision(),
                                        false))))
                .isInstanceOf(SecurityException.class);

        assertThat(registrationState(pending.fixture()))
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(decisionReceiptCount(pending.fixture())).isZero();
        assertThat(resultOutboxCount(pending.fixture()))
                .isEqualTo(resultOutboxBaseline);
    }

    @Test
    void hardLimitRejectsApprovalWhenCapacityIsFull() {
        PendingRegistration pending = pending(
                CalendarRegistrationLimitMode.HARD_LIMIT,
                true,
                "decision-hard-limit");

        assertThatThrownBy(() -> runtime(
                        pending.fixture().peerContext(),
                        () -> decisions.decide(
                                new CalendarRegistrationDecisionCommand(
                                        "hard-limit-decision",
                                        pending.fixture().planId(),
                                        planScope(pending.fixture()),
                                        pending.registration().id(),
                                        CalendarRegistrationDecisionCommand
                                                .Decision.APPROVE,
                                        null,
                                        pending.registration().revision(),
                                        false))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hard-limit");

        assertThat(registrationState(pending.fixture()))
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(bucketCount(pending.fixture(), "committed_count"))
                .isOne();
        assertThat(bucketCount(pending.fixture(), "over_capacity_count"))
                .isZero();
    }

    @Test
    void managerOverrideRequiresExplicitConfirmationBeforeOverCapacity() {
        PendingRegistration pending = pending(
                CalendarRegistrationLimitMode.MANAGER_OVERRIDE,
                true,
                "decision-manager-override");
        long resultOutboxBaseline = resultOutboxCount(pending.fixture());
        CalendarRegistrationDecisionView confirmation = runtime(
                pending.fixture().peerContext(),
                () -> decisions.decide(
                        new CalendarRegistrationDecisionCommand(
                                "override-decision",
                                pending.fixture().planId(),
                                planScope(pending.fixture()),
                                pending.registration().id(),
                                CalendarRegistrationDecisionCommand.Decision
                                        .APPROVE,
                                null,
                                pending.registration().revision(),
                                false)));

        assertThat(confirmation.state())
                .isEqualTo(CalendarRegistrationState.APPROVAL_REQUIRED);
        assertThat(confirmation.overrideConfirmationRequired()).isTrue();
        assertThat(confirmation.warning())
                .isEqualTo("CAPACITY_OVERRIDE_CONFIRMATION_REQUIRED");
        assertThat(bucketCount(pending.fixture(), "committed_count"))
                .isOne();
        assertThat(decisionReceiptCount(pending.fixture())).isZero();
        assertThat(resultOutboxCount(pending.fixture()))
                .isEqualTo(resultOutboxBaseline);

        assertThatThrownBy(() -> runtime(
                        pending.fixture().peerContext(),
                        () -> decisions.decide(
                                new CalendarRegistrationDecisionCommand(
                                        "override-without-reason",
                                        pending.fixture().planId(),
                                        planScope(pending.fixture()),
                                        pending.registration().id(),
                                        CalendarRegistrationDecisionCommand
                                                .Decision.APPROVE,
                                        " ",
                                        pending.registration().revision(),
                                        true))))
                .isInstanceOf(IllegalArgumentException.class);

        CalendarRegistrationDecisionView approved = runtime(
                pending.fixture().peerContext(),
                () -> decisions.decide(
                        new CalendarRegistrationDecisionCommand(
                                "override-decision",
                                pending.fixture().planId(),
                                planScope(pending.fixture()),
                                pending.registration().id(),
                                CalendarRegistrationDecisionCommand.Decision
                                        .APPROVE,
                                "Manager approved a one-seat capacity exception",
                                pending.registration().revision(),
                                true)));

        assertThat(approved.state())
                .isEqualTo(CalendarRegistrationState.COMMITTED);
        assertThat(approved.overrideConfirmationRequired()).isFalse();
        assertThat(bucketCount(pending.fixture(), "committed_count"))
                .isEqualTo(2);
        assertThat(bucketCount(pending.fixture(), "over_capacity_count"))
                .isOne();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT capacity_state
                        FROM calendar_registration_policy
                        WHERE plan_id = ?
                        """,
                        String.class,
                        pending.fixture().planId()))
                .isEqualTo("OVER_CAPACITY");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT decision_reason
                        FROM calendar_registration_history
                        WHERE registration_id = ?
                          AND event_type = 'APPROVAL_APPROVED'
                        """,
                        String.class,
                        pending.registration().id()))
                .isEqualTo(
                        "Manager approved a one-seat capacity exception");
        assertThat(resultOutboxCount(pending.fixture()))
                .isEqualTo(resultOutboxBaseline + 1);
    }

    private PendingRegistration pending(
            CalendarRegistrationLimitMode limitMode,
            boolean fillCapacity,
            String requestPrefix) {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture,
                fixture.recipientContext(),
                requestPrefix + "-recipient-viewer");
        if (fillCapacity) {
            grantWholePlanViewer(
                    fixture,
                    fixture.adminContext(),
                    requestPrefix + "-admin-viewer");
            configurePolicy(
                    fixture,
                    requestPrefix + "-open-policy",
                    Instant.now(clock).plus(Duration.ofHours(2)),
                    CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                    limitMode,
                    0);
            runtime(
                    fixture.adminContext(),
                    () -> registrations.join(
                            new CalendarRegistrationJoinCommand(
                                    requestPrefix + "-capacity-holder",
                                    fixture.planId(),
                                    planScope(fixture))));
            configurePolicy(
                    fixture,
                    requestPrefix + "-closed-policy",
                    Instant.now(clock).minus(Duration.ofHours(1)),
                    CalendarLateJoinPolicy.REQUIRE_APPROVAL,
                    limitMode,
                    1);
        } else {
            configurePolicy(
                    fixture,
                    requestPrefix + "-closed-policy",
                    Instant.now(clock).minus(Duration.ofHours(1)),
                    CalendarLateJoinPolicy.REQUIRE_APPROVAL,
                    limitMode,
                    0);
        }
        CalendarRegistrationView registration = runtime(
                fixture.recipientContext(),
                () -> registrations.join(
                        new CalendarRegistrationJoinCommand(
                                requestPrefix + "-approval-request",
                                fixture.planId(),
                                planScope(fixture))));
        assignManager(fixture, requestPrefix + "-manager");
        assertThat(registration.state())
                .isEqualTo(CalendarRegistrationState.APPROVAL_REQUIRED);
        return new PendingRegistration(fixture, registration);
    }

    private void configurePolicy(
            Fixture fixture,
            String requestId,
            Instant closesAt,
            CalendarLateJoinPolicy latePolicy,
            CalendarRegistrationLimitMode limitMode,
            long expectedRevision) {
        Instant opensAt = Instant.now(clock).minus(Duration.ofHours(2));
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                requestId,
                                fixture.planId(),
                                planScope(fixture),
                                opensAt,
                                closesAt,
                                "Asia/Taipei",
                                1,
                                latePolicy,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                limitMode,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                expectedRevision)));
    }

    private void assignManager(Fixture fixture, String requestId) {
        inContext(
                fixture.ownerContext(),
                () -> organizers.change(
                        new CalendarOrganizerAssignmentChange(
                                requestId,
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

    private String registrationState(Fixture fixture) {
        return jdbc.queryForObject(
                """
                SELECT registration_state
                FROM calendar_registration
                WHERE plan_id = ? AND created_by_user_id = ?
                """,
                String.class,
                fixture.planId(),
                fixture.recipientContext().actorId());
    }

    private String participationState(Fixture fixture) {
        return jdbc.queryForObject(
                """
                SELECT participation_state
                FROM calendar_participation
                WHERE plan_id = ? AND created_by_user_id = ?
                """,
                String.class,
                fixture.planId(),
                fixture.recipientContext().actorId());
    }

    private long decisionHistoryCount(
            Fixture fixture, String eventType) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_history
                WHERE plan_id = ? AND event_type = ?
                """,
                Long.class,
                fixture.planId(),
                eventType);
    }

    private long decisionReceiptCount(Fixture fixture) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_request_receipt
                WHERE plan_id = ?
                  AND request_kind = 'REGISTRATION_DECISION'
                """,
                Long.class,
                fixture.planId());
    }

    private long resultOutboxCount(Fixture fixture) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_outbox
                WHERE plan_id = ?
                  AND event_type = 'REGISTRATION_RESULT'
                """,
                Long.class,
                fixture.planId());
    }

    private record PendingRegistration(
            Fixture fixture,
            CalendarRegistrationView registration) {}
}
