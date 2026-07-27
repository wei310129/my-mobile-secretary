package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarParticipationSeparationIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Test
    void visibilityParticipationWatchAndAdoptionRemainIndependent() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "separation-viewer");

        runtime(
                fixture.recipientContext(),
                () -> {
                    assertThat(jdbc.queryForObject(
                                    """
                                    SELECT count(*) FROM calendar_share
                                    WHERE plan_id = ? AND status = 'ACTIVE'
                                    """,
                                    Long.class,
                                    fixture.planId()))
                            .isOne();
                    assertThat(jdbc.queryForObject(
                                    """
                                    SELECT count(*) FROM calendar_plan
                                    WHERE id = ? AND status = 'ACTIVE'
                                    """,
                                    Long.class,
                                    fixture.planId()))
                            .isOne();
                    assertThat(participations.current(
                                    fixture.planId(), planScope(fixture)))
                            .isEmpty();
                    assertThat(watches.current(
                                    fixture.planId(), planScope(fixture)))
                            .isEmpty();
                    assertThat(adoptions.constraints()).isEmpty();
                    assertThat(routes.current().busyIntervals()).isEmpty();
                    return null;
                });

        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.TENTATIVE,
                "separation-tentative",
                0);
        runtime(
                fixture.recipientContext(),
                () -> {
                    assertThat(participations.current(
                                            fixture.planId(),
                                            planScope(fixture))
                                    .orElseThrow()
                                    .status())
                            .isEqualTo(CalendarParticipationStatus.TENTATIVE);
                    assertThat(watches.current(
                                    fixture.planId(), planScope(fixture)))
                            .isEmpty();
                    assertThat(adoptions.constraints()).isEmpty();
                    return null;
                });

        runtime(
                fixture.recipientContext(),
                () -> watches.subscribe(new CalendarWatchSubscriptionChange(
                        "separation-watch",
                        fixture.planId(),
                        planScope(fixture),
                        0)));
        runtime(
                fixture.recipientContext(),
                () -> {
                    assertThat(watches.current(
                                    fixture.planId(), planScope(fixture)))
                            .isPresent();
                    assertThat(participations.current(
                                            fixture.planId(),
                                            planScope(fixture))
                                    .orElseThrow()
                                    .status())
                            .isEqualTo(CalendarParticipationStatus.TENTATIVE);
                    assertThat(adoptions.constraints()).isEmpty();
                    return null;
                });

        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "separation-committed",
                1);
        runtime(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));
        runtime(
                fixture.recipientContext(),
                () -> {
                    assertThat(adoptions.constraints())
                            .extracting(constraint -> constraint.nodeKey())
                            .containsExactly("boarding");
                    assertThat(watches.current(
                                    fixture.planId(), planScope(fixture)))
                            .isPresent();
                    return null;
                });
    }

    @Test
    void participationAndWatchRowsAreActorPrivateUnderRls() {
        Fixture fixture = fixture();
        grantWholePlanViewer(fixture, fixture.recipientContext(), "rls-viewer");
        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "rls-committed",
                0);
        runtime(
                fixture.recipientContext(),
                () -> watches.subscribe(new CalendarWatchSubscriptionChange(
                        "rls-watch",
                        fixture.planId(),
                        planScope(fixture),
                        0)));

        assertThat(runtimeCount(
                        fixture.recipientContext(),
                        "calendar_participation",
                        fixture.planId()))
                .isOne();
        assertThat(runtimeCount(
                        fixture.recipientContext(),
                        "calendar_routine_subscription",
                        fixture.planId()))
                .isOne();
        assertThat(runtimeCount(
                        fixture.ownerContext(),
                        "calendar_participation",
                        fixture.planId()))
                .isOne();
        assertThat(runtimeCount(
                        fixture.ownerContext(),
                        "calendar_routine_subscription",
                        fixture.planId()))
                .isZero();
        assertThat(runtime(
                        fixture.ownerContext(),
                        () -> jdbc.update(
                                """
                                UPDATE calendar_participation
                                SET participation_state = 'OPTED_OUT'
                                WHERE plan_id = ?
                                """,
                                fixture.planId())))
                .isZero();
        for (var unauthorized :
                List.of(fixture.adminContext(), fixture.peerContext())) {
            assertThat(runtimeCount(
                            unauthorized,
                            "calendar_participation",
                            fixture.planId()))
                    .isZero();
            assertThat(runtimeCount(
                            unauthorized,
                            "calendar_routine_subscription",
                            fixture.planId()))
                    .isZero();
            assertThat(runtime(
                            unauthorized,
                            () -> jdbc.update(
                                    """
                                    UPDATE calendar_participation
                                    SET participation_state = 'OPTED_OUT'
                                    WHERE plan_id = ?
                                    """,
                                    fixture.planId())))
                    .isZero();
        }
    }

    @Test
    void occurrenceMutationIsTypedButRejectedUntilWheelTen() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "occurrence-viewer");
        CalendarParticipationScope unsupportedOccurrence =
                new CalendarParticipationScope(
                        CalendarParticipationScopeType.OCCURRENCE,
                        fixture.nodeId());

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> participations.change(
                                new CalendarParticipationChange(
                                        "occurrence-not-yet-supported",
                                        fixture.planId(),
                                        unsupportedOccurrence,
                                        CalendarParticipationStatus.COMMITTED,
                                        0))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Wheel 10");
        assertThat(count("calendar_participation")).isZero();
    }

    @Test
    void requiredOrCommittedExitRequiresRevisionBoundSkipConfirmation() {
        Fixture required = fixture();
        grantWholePlanViewer(
                required,
                required.recipientContext(),
                "required-exit-viewer");
        inContext(
                required.ownerContext(),
                () -> participationPolicies.change(
                        new CalendarParticipationPolicyChange(
                                "required-exit-policy",
                                required.planId(),
                                planScope(required),
                                CalendarParticipationPolicy.REQUIRED,
                                0)));

        assertThatThrownBy(() -> changeParticipation(
                        required,
                        required.recipientContext(),
                        CalendarParticipationStatus.OPTED_OUT,
                        "required-direct-exit",
                        0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("impact confirmation");
        assertThat(runtime(
                        required.recipientContext(),
                        () -> participations.current(
                                required.planId(), planScope(required))))
                .isEmpty();

        Fixture committed = fixture();
        grantWholePlanViewer(
                committed,
                committed.recipientContext(),
                "committed-exit-viewer");
        changeParticipation(
                committed,
                committed.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "committed-exit-state",
                0);

        assertThatThrownBy(() -> changeParticipation(
                        committed,
                        committed.recipientContext(),
                        CalendarParticipationStatus.DECLINED,
                        "committed-direct-exit",
                        1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("impact confirmation");
        assertThat(runtime(
                                committed.recipientContext(),
                                () -> participations
                                        .current(
                                                committed.planId(),
                                                planScope(committed))
                                        .orElseThrow()
                                        .status()))
                .isEqualTo(CalendarParticipationStatus.COMMITTED);
    }

    @Test
    void runtimeSqlCannotForgeCommittedExitWithoutExactSuppression() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "forged-exit-viewer");
        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "forged-exit-committed",
                0);
        String requestHash =
                CalendarParticipationAccess.hash("forged-exit-request");
        String payloadHash =
                CalendarParticipationAccess.hash("forged-exit-payload");
        String expectedFailure =
                "ERROR: committed or required participation exit requires "
                        + "exact confirmed suppression";

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> {
                            jdbc.update(
                                    """
                                    UPDATE calendar_participation
                                    SET participation_state = 'OPTED_OUT',
                                        participation_revision = 2,
                                        operation_request_hash = ?,
                                        operation_payload_hash = ?,
                                        updated_at =
                                            updated_at + INTERVAL '1 second'
                                    WHERE plan_id = ?
                                      AND target_scope = 'PLAN'
                                      AND participation_revision = 1
                                    """,
                                    requestHash,
                                    payloadHash,
                                    fixture.planId());
                            jdbc.update(
                                    """
                                    INSERT INTO calendar_participation_history (
                                        id, participation_id, plan_id,
                                        activity_id, target_scope, event_type,
                                        previous_state, current_state,
                                        participation_revision,
                                        source_revision,
                                        operation_request_hash,
                                        operation_payload_hash, occurred_at,
                                        workspace_id, created_by_user_id,
                                        source_created_by_user_id)
                                    SELECT gen_random_uuid(), id, plan_id,
                                           activity_id, target_scope,
                                           'STATE_CHANGED', 'COMMITTED',
                                           'OPTED_OUT', 2, source_revision,
                                           ?, ?, updated_at,
                                           workspace_id, created_by_user_id,
                                           source_created_by_user_id
                                    FROM calendar_participation
                                    WHERE plan_id = ?
                                      AND target_scope = 'PLAN'
                                      AND participation_revision = 2
                                    """,
                                    requestHash,
                                    payloadHash,
                                    fixture.planId());
                            return null;
                        }))
                .hasStackTraceContaining(expectedFailure);
        assertThat(runtime(
                                fixture.recipientContext(),
                                () -> participations
                                        .current(
                                                fixture.planId(),
                                                planScope(fixture))
                                        .orElseThrow()
                                        .status()))
                .isEqualTo(CalendarParticipationStatus.COMMITTED);
    }
}
