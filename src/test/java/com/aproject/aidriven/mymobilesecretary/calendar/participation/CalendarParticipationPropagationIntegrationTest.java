package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeCapabilityGrant;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeCapabilityView;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarAuthoritativeTimeChange;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarSharePermission;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareRoleChange;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarParticipationPropagationIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Test
    void authoritativeRevisionPropagatesByParticipationAndWatchContract() {
        Fixture fixture = fixture();
        WorkspaceContext committed = fixture.recipientContext();
        WorkspaceContext tentative = addActor(fixture, "tentative actor");
        WorkspaceContext watching = addActor(fixture, "watching actor");
        WorkspaceContext declined = addActor(fixture, "declined actor");
        WorkspaceContext optedOut = addActor(fixture, "opted-out actor");
        WorkspaceContext editor = addActor(fixture, "authoritative editor");

        int sequence = 0;
        for (WorkspaceContext actor :
                List.of(
                        committed,
                        tentative,
                        watching,
                        declined,
                        optedOut)) {
            grantWholePlanViewer(
                    fixture, actor, "propagation-viewer-" + sequence++);
        }
        changeParticipation(
                fixture,
                committed,
                CalendarParticipationStatus.COMMITTED,
                "propagation-committed",
                0);
        changeParticipation(
                fixture,
                tentative,
                CalendarParticipationStatus.TENTATIVE,
                "propagation-tentative",
                0);
        changeParticipation(
                fixture,
                declined,
                CalendarParticipationStatus.DECLINED,
                "propagation-declined",
                0);
        changeParticipation(
                fixture,
                optedOut,
                CalendarParticipationStatus.OPTED_OUT,
                "propagation-opted-out",
                0);
        for (WorkspaceContext adopter : List.of(committed, tentative)) {
            runtime(
                    adopter,
                    () -> adoptions.adoptPlan(
                            fixture.planId(), List.of("boarding")));
        }
        ReminderFixture reminders =
                seedParticipantReminders(fixture, committed);
        runtime(
                watching,
                () -> watches.subscribe(new CalendarWatchSubscriptionChange(
                        "propagation-watch",
                        fixture.planId(),
                        planScope(fixture),
                        0)));

        CalendarShareView editorShare = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "propagation-editor-duplicate-safe",
                        fixture.planId(),
                        editor.actorId(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "propagation-editor-role",
                        editorShare.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "propagation-capability",
                                editorShare.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.NODE,
                                fixture.nodeId(),
                                2)));

        runtime(
                editor,
                () -> authoritativeMutations.reviseAbsoluteTime(
                        new CalendarAuthoritativeTimeChange(
                                "propagation-time",
                                capability.id(),
                                fixture.nodeId(),
                                START.plusSeconds(1800),
                                1,
                                "船班延後",
                                "provider-update")));
        for (WorkspaceContext recipient :
                List.of(
                        committed,
                        tentative,
                        watching,
                        declined,
                        optedOut)) {
            runtime(
                    recipient,
                    () -> personalProjections.process(
                            recipient.actorId()));
        }

        CalendarParticipationView committedView = current(fixture, committed);
        assertThat(committedView.propagationState())
                .isEqualTo(CalendarParticipationPropagationState
                        .AUTO_APPLIED_AWAITING_ACKNOWLEDGEMENT);
        assertThat(committedView.awaitingAcknowledgement()).isTrue();
        runtime(
                committed,
                () -> {
                    assertThat(adoptions.constraints())
                            .singleElement()
                            .satisfies(constraint -> {
                                assertThat(constraint.nodeRevision())
                                        .isEqualTo(2);
                                assertThat(constraint.effectiveTime())
                                        .isEqualTo(START.plusSeconds(1800));
                            });
                    return null;
                });

        CalendarParticipationView tentativeView = current(fixture, tentative);
        assertThat(tentativeView.propagationState())
                .isEqualTo(
                        CalendarParticipationPropagationState.REVIEW_REQUIRED);
        assertThat(tentativeView.awaitingAcknowledgement()).isFalse();
        runtime(
                tentative,
                () -> {
                    assertThat(adoptions.constraints()).isEmpty();
                    assertThat(routes.current().busyIntervals()).isEmpty();
                    return null;
                });

        assertThat(runtime(
                        watching,
                        () -> participations.current(
                                fixture.planId(), planScope(fixture))))
                .isEmpty();
        assertThat(runtime(watching, adoptions::constraints)).isEmpty();
        assertThat(current(fixture, declined).propagationState())
                .isEqualTo(CalendarParticipationPropagationState.SUPPRESSED);
        assertThat(current(fixture, optedOut).propagationState())
                .isEqualTo(CalendarParticipationPropagationState.SUPPRESSED);
        assertThat(runtime(declined, adoptions::constraints)).isEmpty();
        assertThat(runtime(optedOut, adoptions::constraints)).isEmpty();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT node_revision
                        FROM calendar_adoption_node selected
                        JOIN calendar_adoption adoption
                          ON adoption.id = selected.adoption_id
                         AND adoption.workspace_id =
                             selected.workspace_id
                         AND adoption.created_by_user_id =
                             selected.created_by_user_id
                        WHERE selected.node_id = ?
                          AND selected.created_by_user_id = ?
                        """,
                        Long.class,
                        fixture.nodeId(),
                        committed.actorId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT snapshot.source_plan_revision
                        FROM calendar_personal_projection_snapshot snapshot
                        JOIN calendar_plan plan
                          ON plan.id = snapshot.plan_id
                         AND plan.workspace_id = snapshot.workspace_id
                         AND plan.created_by_user_id =
                             snapshot.source_created_by_user_id
                        WHERE snapshot.plan_id = ?
                          AND snapshot.created_by_user_id = ?
                          AND snapshot.projection_status = 'ACTIVE'
                        """,
                        Long.class,
                        fixture.planId(),
                        committed.actorId()))
                .isEqualTo(jdbc.queryForObject(
                        """
                        SELECT version + 1
                        FROM calendar_plan
                        WHERE id = ?
                        """,
                        Long.class,
                        fixture.planId()));
        assertReminderConsequences(
                committed, reminders);
        long committedOutboxBeforeReplay =
                recipientOutboxCount(committed);
        long occurrenceCountBeforeReplay = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_reminder_occurrence
                WHERE workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                committed.workspaceId(),
                committed.actorId());
        assertThat(runtime(
                        committed,
                        () -> personalProjections.process(
                                committed.actorId())))
                .isZero();
        assertThat(recipientOutboxCount(committed))
                .isEqualTo(committedOutboxBeforeReplay);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_reminder_occurrence
                        WHERE workspace_id = ? AND created_by_user_id = ?
                        """,
                        Long.class,
                        committed.workspaceId(),
                        committed.actorId()))
                .isEqualTo(occurrenceCountBeforeReplay);

        assertThat(recipientOutboxCount(committed))
                .as("COMMITTED receives mandatory authoritative update")
                .isOne();
        assertThat(recipientOutboxCount(tentative))
                .as("TENTATIVE receives review update")
                .isOne();
        assertThat(recipientOutboxCount(watching))
                .as("WATCHING receives routine update")
                .isOne();
        assertThat(jdbc.queryForList(
                        """
                        SELECT created_by_user_id
                        FROM calendar_participation_outbox
                        WHERE plan_id = ? AND source_revision = 2
                          AND event_type IN (
                              'MANDATORY_UPDATE',
                              'REVIEW_REQUIRED_UPDATE',
                              'ROUTINE_UPDATE')
                        ORDER BY created_by_user_id
                        """,
                        java.util.UUID.class,
                        fixture.planId()))
                .containsExactlyInAnyOrder(
                        committed.actorId(),
                        tentative.actorId(),
                        watching.actorId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_participation_outbox
                        WHERE plan_id = ? AND source_revision = 2
                          AND created_by_user_id IN (?, ?)
                        """,
                        Long.class,
                        fixture.planId(),
                        declined.actorId(),
                        optedOut.actorId()))
                .isZero();
    }

    private long recipientOutboxCount(WorkspaceContext actor) {
        return runtime(
                actor,
                () -> jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_participation_outbox
                        WHERE event_type IN (
                            'MANDATORY_UPDATE',
                            'REVIEW_REQUIRED_UPDATE',
                            'ROUTINE_UPDATE')
                        """,
                        Long.class));
    }

    private ReminderFixture seedParticipantReminders(
            Fixture fixture, WorkspaceContext actor) {
        UUID relativeRule = UUID.randomUUID();
        UUID absoluteRule = UUID.randomUUID();
        UUID relativeOccurrence = UUID.randomUUID();
        UUID absoluteOccurrence = UUID.randomUUID();
        UUID personalSnapshotId = jdbc.queryForObject(
                """
                SELECT id
                FROM calendar_personal_projection_snapshot
                WHERE plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND projection_status = 'ACTIVE'
                """,
                UUID.class,
                fixture.planId(),
                actor.workspaceId(),
                actor.actorId());
        jdbc.update(
                """
                INSERT INTO calendar_reminder_rule (
                    id, plan_id, node_id, owner_kind, rule_kind,
                    offset_seconds, absolute_fire_at, delivery_mode,
                    ack_interval_seconds, max_alerts, preferred_channel,
                    status, revision, version, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id,
                    personal_projection_snapshot_id)
                VALUES
                    (?, ?, ?, 'PERSONAL', 'RELATIVE', -600, NULL,
                     'ONCE', NULL, NULL, NULL, 'ACTIVE', 1, 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?, ?),
                    (?, ?, ?, 'PERSONAL', 'ABSOLUTE', NULL, ?,
                     'ONCE', NULL, NULL, NULL, 'ACTIVE', 1, 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?, ?)
                """,
                relativeRule,
                fixture.planId(),
                fixture.nodeId(),
                actor.workspaceId(),
                actor.actorId(),
                fixture.ownerContext().actorId(),
                personalSnapshotId,
                absoluteRule,
                fixture.planId(),
                fixture.nodeId(),
                java.sql.Timestamp.from(START.minusSeconds(900)),
                actor.workspaceId(),
                actor.actorId(),
                fixture.ownerContext().actorId(),
                personalSnapshotId);
        jdbc.update(
                """
                INSERT INTO calendar_reminder_occurrence (
                    id, rule_id, plan_id, node_id, node_revision,
                    rule_revision, sequence_number, scheduled_at,
                    status, version, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES
                    (?, ?, ?, ?, 1, 1, 0, ?, 'PENDING', 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?),
                    (?, ?, ?, ?, 1, 1, 0, ?, 'ENQUEUED', 0,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?, ?)
                """,
                relativeOccurrence,
                relativeRule,
                fixture.planId(),
                fixture.nodeId(),
                java.sql.Timestamp.from(START.minusSeconds(600)),
                actor.workspaceId(),
                actor.actorId(),
                fixture.ownerContext().actorId(),
                absoluteOccurrence,
                absoluteRule,
                fixture.planId(),
                fixture.nodeId(),
                java.sql.Timestamp.from(START.minusSeconds(900)),
                actor.workspaceId(),
                actor.actorId(),
                fixture.ownerContext().actorId());
        return new ReminderFixture(
                relativeRule,
                absoluteRule,
                relativeOccurrence,
                absoluteOccurrence);
    }

    private void assertReminderConsequences(
            WorkspaceContext actor,
            ReminderFixture reminders) {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT status
                        FROM calendar_reminder_rule
                        WHERE id = ?
                        """,
                        String.class,
                        reminders.relativeRule()))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT status
                        FROM calendar_reminder_rule
                        WHERE id = ?
                        """,
                        String.class,
                        reminders.absoluteRule()))
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForList(
                        """
                        SELECT status
                        FROM calendar_reminder_occurrence
                        WHERE id IN (?, ?)
                        ORDER BY id
                        """,
                        String.class,
                        reminders.relativeOccurrence(),
                        reminders.absoluteOccurrence()))
                .containsOnly("CANCELED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT scheduled_at
                        FROM calendar_reminder_occurrence
                        WHERE rule_id = ? AND node_revision = 2
                          AND status = 'PENDING'
                          AND workspace_id = ?
                          AND created_by_user_id = ?
                        """,
                        java.sql.Timestamp.class,
                        reminders.relativeRule(),
                        actor.workspaceId(),
                        actor.actorId())
                        .toInstant())
                .isEqualTo(START.plusSeconds(1200));
    }

    private record ReminderFixture(
            UUID relativeRule,
            UUID absoluteRule,
            UUID relativeOccurrence,
            UUID absoluteOccurrence) {}

    private CalendarParticipationView current(
            Fixture fixture, WorkspaceContext actor) {
        return runtime(
                actor,
                () -> participations
                        .current(fixture.planId(), planScope(fixture))
                        .orElseThrow());
    }
}
