package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeCapabilityGrant;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeCapabilityView;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeTimeChange;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarSharePermission;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareRoleChange;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareView;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarSkipIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Test
    void committedSkipRequiresRevisionBoundPreviewAndIsAtomicAndReplaySafe() {
        Fixture fixture = fixture();
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "skip-viewer",
                        fixture.planId(),
                        fixture.recipientContext().actorId(),
                        1));
        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "skip-committed",
                0);
        runtime(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));
        runtime(
                fixture.recipientContext(),
                () -> watches.subscribe(new CalendarWatchSubscriptionChange(
                        "skip-watch",
                        fixture.planId(),
                        planScope(fixture),
                        0)));

        CalendarSkipImpactPreview preview = runtime(
                fixture.recipientContext(),
                () -> skips.preview(new CalendarSkipPreviewRequest(
                        fixture.planId(), planScope(fixture))));

        assertThat(preview.strongConfirmationRequired()).isTrue();
        assertThat(preview.sourceRevision()).isEqualTo(1);
        assertThat(preview.affectedNodeIds())
                .containsExactly(fixture.nodeId());
        assertThat(preview.confirmationToken()).isNotBlank();
        assertThat(preview.digest()).isNotBlank();

        long participationBefore = count("calendar_participation");
        long adoptionBefore = count("calendar_adoption");
        long outboxBefore = count("calendar_participation_outbox");
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> skips.confirm(new CalendarSkipConfirmation(
                                "skip-forged",
                                preview.confirmationToken(),
                                "forged-digest",
                                preview.sourceRevision()))))
                .isInstanceOf(BusinessException.class);
        assertThat(count("calendar_participation"))
                .isEqualTo(participationBefore);
        assertThat(count("calendar_adoption")).isEqualTo(adoptionBefore);
        assertThat(count("calendar_participation_outbox"))
                .isEqualTo(outboxBefore);

        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "skip-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "skip-authoritative",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.NODE,
                                fixture.nodeId(),
                                2)));
        runtime(
                fixture.recipientContext(),
                () -> authoritativeMutations.reviseAbsoluteTime(
                        new CalendarAuthoritativeTimeChange(
                                "skip-source-revision",
                                capability.id(),
                                fixture.nodeId(),
                                START.plusSeconds(900),
                                1,
                                "確認前來源改版",
                                "integration-test")));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> skips.confirm(new CalendarSkipConfirmation(
                                "skip-stale",
                                preview.confirmationToken(),
                                preview.digest(),
                                preview.sourceRevision()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("preview");

        CalendarSkipImpactPreview refreshed = runtime(
                fixture.recipientContext(),
                () -> skips.preview(new CalendarSkipPreviewRequest(
                        fixture.planId(), planScope(fixture))));
        CalendarSkipConfirmation confirmation = new CalendarSkipConfirmation(
                "skip-confirm",
                refreshed.confirmationToken(),
                refreshed.digest(),
                refreshed.sourceRevision());
        CalendarSkipResult result = runtime(
                fixture.recipientContext(),
                () -> skips.confirm(confirmation));
        CalendarSkipResult replay = runtime(
                fixture.recipientContext(),
                () -> skips.confirm(confirmation));

        assertThat(result.participationStatus())
                .isEqualTo(CalendarParticipationStatus.OPTED_OUT);
        assertThat(result.adoptionRemoved()).isTrue();
        assertThat(result.routineMessagesSuppressed()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.participationStatus())
                .isEqualTo(CalendarParticipationStatus.OPTED_OUT);
        runtime(
                fixture.recipientContext(),
                () -> {
                    assertThat(adoptions.constraints()).isEmpty();
                    assertThat(routes.current().busyIntervals()).isEmpty();
                    assertThat(watches.current(
                                    fixture.planId(), planScope(fixture)))
                            .isEmpty();
                    return null;
                });
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_participation_outbox
                        WHERE plan_id = ? AND event_type = 'SKIP_CONFIRMED'
                        """,
                        Long.class,
                        fixture.planId()))
                .isOne();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT confirmation.expected_participation_revision
                                   = suppression.participation_revision
                               AND confirmation.expected_projection_revision
                                   = suppression.projection_revision
                               AND confirmation.expected_source_revision
                                   = suppression.source_revision
                               AND participation.participation_revision
                                   = suppression.participation_revision
                        FROM calendar_skip_confirmation confirmation
                        JOIN calendar_projection_suppression suppression
                          ON suppression.confirmation_id = confirmation.id
                        JOIN calendar_participation participation
                          ON participation.id =
                                confirmation.participation_id
                        WHERE confirmation.plan_id = ?
                          AND confirmation.consumed_at IS NOT NULL
                        """,
                        Boolean.class,
                        fixture.planId()))
                .isTrue();
    }

    @Test
    void optionalNeverParticipatedScopeCanBeIgnoredWithoutStrongConfirmation() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "optional-viewer");

        CalendarSkipImpactPreview preview = runtime(
                fixture.recipientContext(),
                () -> skips.preview(new CalendarSkipPreviewRequest(
                        fixture.planId(), planScope(fixture))));

        assertThat(preview.strongConfirmationRequired()).isFalse();
        assertThat(preview.affectedNodeIds()).isEmpty();
        assertThat(preview.affectedReminderIds()).isEmpty();
        assertThat(preview.retainedLinkedItemIds()).isEmpty();
    }
}
