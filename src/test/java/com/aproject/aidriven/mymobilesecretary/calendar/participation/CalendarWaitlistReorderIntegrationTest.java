package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarWaitlistReorderIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarRegistrationService registrations;
    @Autowired private CalendarOrganizerAssignmentService organizerAssignments;
    @Autowired private CalendarWaitlistService waitlists;
    @Autowired private CalendarWaitlistReorderService reorderService;
    @Autowired private Clock clock;

    @Test
    void scopedRosterManagerReordersCompleteWaitingQueueWithStableReplay() {
        Setup setup = waitingQueue(3);
        assignRosterManager(setup.fixture());
        List<Entry> original = waitingEntries(setup.fixture());
        List<CalendarWaitlistReorderCommand.Entry> reversed =
                new ArrayList<>();
        for (int index = original.size() - 1; index >= 0; index--) {
            Entry entry = original.get(index);
            reversed.add(new CalendarWaitlistReorderCommand.Entry(
                    entry.id(), entry.revision()));
        }
        CalendarWaitlistReorderCommand command =
                new CalendarWaitlistReorderCommand(
                        "manager-reorder",
                        setup.fixture().planId(),
                        planScope(setup.fixture()),
                        reversed,
                        "優先處理已確認能準時抵達者");

        assertThatThrownBy(() -> inContext(
                        setup.fixture().recipientContext(),
                        () -> reorderService.reorder(command)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> inContext(
                        setup.fixture().adminContext(),
                        () -> reorderService.reorder(
                                new CalendarWaitlistReorderCommand(
                                        "missing-reason",
                                        setup.fixture().planId(),
                                        planScope(setup.fixture()),
                                        reversed,
                                        " "))))
                .isInstanceOf(IllegalArgumentException.class);

        CalendarWaitlistReorderView changed = inContext(
                setup.fixture().adminContext(),
                () -> reorderService.reorder(command));
        CalendarWaitlistReorderView replay = inContext(
                setup.fixture().adminContext(),
                () -> reorderService.reorder(command));

        assertThat(replay).isEqualTo(changed);
        assertThat(changed.entries())
                .extracting(CalendarWaitlistReorderView.Entry::entryId)
                .containsExactlyElementsOf(
                        reversed.stream()
                                .map(CalendarWaitlistReorderCommand.Entry::entryId)
                                .toList());
        assertThat(changed.entries())
                .extracting(CalendarWaitlistReorderView.Entry::queueSequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(changed.entries())
                .extracting(CalendarWaitlistReorderView.Entry::revision)
                .containsOnly(2L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_request_receipt
                        WHERE request_kind = 'WAITLIST_REORDER'
                          AND operation_request_hash = ?
                        """,
                        Long.class,
                        CalendarParticipationAccess.hash("manager-reorder")))
                .isOne();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_waitlist_reorder_audit
                        WHERE operation_request_hash = ?
                          AND reason = '優先處理已確認能準時抵達者'
                        """,
                        Long.class,
                        CalendarParticipationAccess.hash("manager-reorder")))
                .isEqualTo(3);

        List<CalendarWaitlistReorderCommand.Entry> stale =
                changed.entries().stream()
                        .map(entry -> new CalendarWaitlistReorderCommand.Entry(
                                entry.entryId(), entry.revision() - 1))
                        .toList();
        assertThatThrownBy(() -> inContext(
                        setup.fixture().adminContext(),
                        () -> reorderService.reorder(
                                new CalendarWaitlistReorderCommand(
                                        "stale-reorder",
                                        setup.fixture().planId(),
                                        planScope(setup.fixture()),
                                        stale,
                                        "過期畫面不可覆寫新順序"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reload");
    }

    @Test
    void activeOfferPreventsAnyQueueReorder() {
        Setup setup = waitingQueue(2);
        assignRosterManager(setup.fixture());
        expandCapacity(setup.fixture(), 2);
        inContext(
                setup.fixture().adminContext(),
                () -> waitlists.offerNext(
                        "offer-before-reorder",
                        setup.fixture().planId(),
                        planScope(setup.fixture())));
        List<CalendarWaitlistReorderCommand.Entry> waiting =
                waitingEntries(setup.fixture()).stream()
                        .map(entry -> new CalendarWaitlistReorderCommand.Entry(
                                entry.id(), entry.revision()))
                        .toList();

        assertThatThrownBy(() -> inContext(
                        setup.fixture().adminContext(),
                        () -> reorderService.reorder(
                                new CalendarWaitlistReorderCommand(
                                        "reorder-with-offer",
                                        setup.fixture().planId(),
                                        planScope(setup.fixture()),
                                        waiting,
                                        "已有 offer 時不得改變排隊承諾"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("offer");
    }

    private Setup waitingQueue(int waitingCount) {
        Fixture fixture = fixture();
        configurePolicy(fixture, 1, 0);
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "reorder-committed-viewer");
        runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "reorder-fill-seat",
                        fixture.planId(),
                        planScope(fixture))));
        List<WorkspaceContext> waitingActors = new ArrayList<>();
        waitingActors.add(fixture.peerContext());
        while (waitingActors.size() < waitingCount) {
            waitingActors.add(addActor(
                    fixture, "waitlist reorder actor " + waitingActors.size()));
        }
        for (int index = 0; index < waitingActors.size(); index++) {
            WorkspaceContext actor = waitingActors.get(index);
            grantWholePlanViewer(
                    fixture, actor, "reorder-waiting-viewer-" + index);
            int actorIndex = index;
            runtime(
                    actor,
                    () -> registrations.join(
                            new CalendarRegistrationJoinCommand(
                                    "reorder-waiting-join-" + actorIndex,
                                    fixture.planId(),
                                    planScope(fixture))));
        }
        return new Setup(fixture);
    }

    private void assignRosterManager(Fixture fixture) {
        inContext(
                fixture.ownerContext(),
                () -> organizerAssignments.change(
                        new CalendarOrganizerAssignmentChange(
                                "assign-reorder-manager",
                                fixture.planId(),
                                planScope(fixture),
                                fixture.adminContext().actorId(),
                                true,
                                false,
                                CalendarOrganizerAssignmentAction.ASSIGN,
                                0)));
    }

    private List<Entry> waitingEntries(Fixture fixture) {
        return jdbc.query(
                """
                SELECT id, queue_sequence, entry_revision
                FROM calendar_waitlist_entry
                WHERE plan_id = ? AND entry_state = 'WAITING'
                ORDER BY queue_sequence, id
                """,
                (row, ignored) -> new Entry(
                        row.getObject("id", UUID.class),
                        row.getLong("queue_sequence"),
                        row.getLong("entry_revision")),
                fixture.planId());
    }

    private void configurePolicy(
            Fixture fixture, int capacity, long expectedRevision) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "reorder-policy-"
                                        + fixture.planId()
                                        + "-"
                                        + expectedRevision,
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                now.plus(Duration.ofHours(2)),
                                "Asia/Taipei",
                                capacity,
                                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                                true,
                                CalendarWaitlistPromotionMode.MANUAL_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                expectedRevision)));
    }

    private void expandCapacity(Fixture fixture, int capacity) {
        configurePolicy(fixture, capacity, 1);
    }

    private record Setup(Fixture fixture) {}

    private record Entry(UUID id, long sequence, long revision) {}
}
