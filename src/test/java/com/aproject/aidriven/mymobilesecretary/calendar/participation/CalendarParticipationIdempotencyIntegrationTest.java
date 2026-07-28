package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

class CalendarParticipationIdempotencyIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Test
    void participationReplayReturnsItsRecordedStateAfterLaterMutation() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "participation-idem-viewer");
        CalendarParticipationChange first = new CalendarParticipationChange(
                "participation-idem-a",
                fixture.planId(),
                planScope(fixture),
                CalendarParticipationStatus.TENTATIVE,
                0);

        CalendarParticipationView recorded = runtime(
                fixture.recipientContext(),
                () -> participations.change(first));
        CalendarParticipationView later = runtime(
                fixture.recipientContext(),
                () -> participations.change(new CalendarParticipationChange(
                        "participation-idem-b",
                        fixture.planId(),
                        planScope(fixture),
                        CalendarParticipationStatus.COMMITTED,
                        1)));
        CalendarParticipationView replay = runtime(
                fixture.recipientContext(),
                () -> participations.change(first));

        assertThat(recorded.status())
                .isEqualTo(CalendarParticipationStatus.TENTATIVE);
        assertThat(recorded.revision()).isOne();
        assertThat(later.status())
                .isEqualTo(CalendarParticipationStatus.COMMITTED);
        assertThat(later.revision()).isEqualTo(2);
        assertThat(replay).isEqualTo(recorded);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> participations.change(
                                new CalendarParticipationChange(
                                        "participation-idem-a",
                                        fixture.planId(),
                                        planScope(fixture),
                                        CalendarParticipationStatus.DECLINED,
                                        2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("request key");
        assertThat(runtime(
                                fixture.recipientContext(),
                                () -> participations.current(
                                        fixture.planId(),
                                        planScope(fixture)))
                        .orElseThrow())
                .isEqualTo(later);
    }

    @Test
    void watchReplayReturnsItsRecordedLifecycleAfterLaterMutation() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "watch-idem-viewer");
        CalendarWatchSubscriptionChange first =
                new CalendarWatchSubscriptionChange(
                        "watch-idem-a",
                        fixture.planId(),
                        planScope(fixture),
                        0);

        CalendarWatchSubscriptionView recorded = runtime(
                fixture.recipientContext(),
                () -> watches.subscribe(first));
        CalendarWatchSubscriptionView later = runtime(
                fixture.recipientContext(),
                () -> watches.unsubscribe(
                        new CalendarWatchSubscriptionChange(
                                "watch-idem-b",
                                fixture.planId(),
                                planScope(fixture),
                                1)));
        CalendarWatchSubscriptionView replay = runtime(
                fixture.recipientContext(),
                () -> watches.subscribe(first));

        assertThat(recorded.active()).isTrue();
        assertThat(recorded.revision()).isOne();
        assertThat(later.active()).isFalse();
        assertThat(later.revision()).isEqualTo(2);
        assertThat(replay).isEqualTo(recorded);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> watches.unsubscribe(
                                new CalendarWatchSubscriptionChange(
                                        "watch-idem-a",
                                        fixture.planId(),
                                        planScope(fixture),
                                        2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("request key");
        assertThat(jdbc.queryForMap(
                        """
                        SELECT subscription_state, subscription_revision
                        FROM calendar_routine_subscription
                        WHERE plan_id = ? AND created_by_user_id = ?
                        """,
                        fixture.planId(),
                        fixture.recipientContext().actorId()))
                .containsEntry("subscription_state", "UNSUBSCRIBED")
                .containsEntry("subscription_revision", 2L);
    }

    @Test
    void policyReplayUsesAppendOnlyReceiptAndOldPayloadStillConflicts() {
        Fixture fixture = fixture();
        CalendarParticipationPolicyChange first =
                new CalendarParticipationPolicyChange(
                        "policy-idem-a",
                        fixture.planId(),
                        planScope(fixture),
                        CalendarParticipationPolicy.REQUIRED,
                        0);

        CalendarParticipationPolicyView recorded = inContext(
                fixture.ownerContext(),
                () -> participationPolicies.change(first));
        CalendarParticipationPolicyView later = inContext(
                fixture.ownerContext(),
                () -> participationPolicies.change(
                        new CalendarParticipationPolicyChange(
                                "policy-idem-b",
                                fixture.planId(),
                                planScope(fixture),
                                CalendarParticipationPolicy.OPTIONAL,
                                1)));
        CalendarParticipationPolicyView replay = inContext(
                fixture.ownerContext(),
                () -> participationPolicies.change(first));

        assertThat(recorded.policy())
                .isEqualTo(CalendarParticipationPolicy.REQUIRED);
        assertThat(recorded.revision()).isOne();
        assertThat(later.policy())
                .isEqualTo(CalendarParticipationPolicy.OPTIONAL);
        assertThat(later.revision()).isEqualTo(2);
        assertThat(replay).isEqualTo(recorded);
        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> participationPolicies.change(
                                new CalendarParticipationPolicyChange(
                                        "policy-idem-a",
                                        fixture.planId(),
                                        planScope(fixture),
                                        CalendarParticipationPolicy
                                                .RECOMMENDED,
                                        2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("request key");
        assertThat(inContext(
                                fixture.ownerContext(),
                                () -> participationPolicies.current(
                                        fixture.planId(),
                                        planScope(fixture)))
                        .orElseThrow())
                .isEqualTo(later);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_participation_request_receipt
                        WHERE operation_kind = 'POLICY_CHANGE'
                          AND plan_id = ?
                        """,
                        Long.class,
                        fixture.planId()))
                .isEqualTo(2);
    }
}
