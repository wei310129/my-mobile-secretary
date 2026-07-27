package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarSelectedScopeQueryService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareView;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarPersonalSnapshotRetentionIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired CalendarSelectedScopeQueryService selectedScope;

    @Test
    void shareRevocationHidesSourceButRetainsFrozenPersonalAdoptionSnapshot() {
        Fixture fixture = fixture();
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "snapshot-viewer",
                        fixture.planId(),
                        fixture.recipientContext().actorId(),
                        1));
        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "snapshot-committed",
                0);
        runtime(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));
        var before = runtime(
                fixture.recipientContext(), adoptions::constraints);
        assertThat(before).singleElement().satisfies(constraint -> {
            assertThat(constraint.nodeKey()).isEqualTo("boarding");
            assertThat(constraint.effectiveTime()).isEqualTo(START);
            assertThat(constraint.nodeRevision()).isEqualTo(1);
        });

        inContext(
                fixture.ownerContext(),
                () -> shares.revoke(
                        "snapshot-revoke", share.id(), share.revision()));
        runtime(
                fixture.recipientContext(),
                () -> personalProjections.process(
                        fixture.recipientContext().actorId()));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> selectedScope.get(fixture.planId())))
                .isInstanceOfAny(
                        NotFoundException.class, BusinessException.class);
        var retained = runtime(
                fixture.recipientContext(), adoptions::constraints);
        assertThat(retained).containsExactlyElementsOf(before);
        assertThat(runtime(
                                fixture.recipientContext(),
                                () -> participations.current(
                                        fixture.planId(),
                                        planScope(fixture)))
                        .orElseThrow()
                        .status())
                .isEqualTo(CalendarParticipationStatus.COMMITTED);
        assertThat(runtimeCount(
                        fixture.recipientContext(),
                        "calendar_personal_projection_snapshot",
                        fixture.planId()))
                .isEqualTo(2);
        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM calendar_personal_projection_snapshot
                                WHERE plan_id = ?
                                  AND projection_status =
                                      'RETAINED_NO_SOURCE_ACCESS'
                                """,
                                Long.class,
                                fixture.planId())))
                .isOne();
        assertThat(runtimeCount(
                        fixture.ownerContext(),
                        "calendar_personal_projection_snapshot",
                        fixture.planId()))
                .isZero();
        assertThat(runtimeCount(
                        fixture.adminContext(),
                        "calendar_personal_projection_snapshot",
                        fixture.planId()))
                .isZero();
    }
}
