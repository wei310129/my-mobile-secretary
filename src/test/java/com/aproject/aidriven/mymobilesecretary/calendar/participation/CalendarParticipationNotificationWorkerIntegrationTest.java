package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarSourceMutationSignalService;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationOutboxService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarParticipationNotificationWorkerIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired
    private CalendarParticipationNotificationProcessor notifications;

    @Autowired
    private NotificationOutboxService notificationOutbox;

    @Autowired
    private CalendarTimeNodeRepository nodes;

    @Autowired
    private CalendarSourceMutationSignalService sourceMutationSignals;

    @Test
    void stableDeliveryOnlyBecomesSentAfterGenericOutboxCompletes() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture,
                fixture.recipientContext(),
                "notification-viewer");
        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "notification-committed",
                0);
        runtime(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));

        runtime(
                fixture.ownerContext(),
                () -> {
                    CalendarTimeNodeEntity node = nodes
                            .findByPlanIdAndNodeKeyAndWorkspaceIdAndCreatedByUserId(
                                    fixture.planId(),
                                    "boarding",
                                    fixture.ownerContext().workspaceId(),
                                    fixture.ownerContext().actorId())
                            .orElseThrow();
                    Instant changedAt = START.plusSeconds(1);
                    node.reviseAbsolute(
                            START.plusSeconds(600), 1, changedAt);
                    nodes.saveAndFlush(node);
                    sourceMutationSignals.recordOwnerGeneral(
                            node,
                            CalendarSourceMutationSignalService.MutationKind
                                    .NODE_TIME,
                            changedAt);
                    return null;
                });
        runtime(
                fixture.recipientContext(),
                () -> personalProjections.process(
                        fixture.recipientContext().actorId()));

        UUID calendarOutboxId = runtime(
                fixture.recipientContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT id
                        FROM calendar_participation_outbox
                        WHERE plan_id = ?
                          AND event_type =
                              'REVIEW_REQUIRED_UPDATE'
                        """,
                        UUID.class,
                        fixture.planId()));
        long snapshotsBeforeDelivery = runtime(
                fixture.recipientContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_personal_projection_snapshot
                        WHERE plan_id = ?
                        """,
                        Long.class,
                        fixture.planId()));

        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> notifications.process(
                                fixture.recipientContext().actorId())))
                .isEqualTo(1);
        assertThat(calendarDeliveryStatus(
                        fixture, calendarOutboxId))
                .isEqualTo("PENDING");
        assertThat(notificationCount(
                        fixture,
                        CalendarParticipationNotificationMessageFactory
                                .deliveryKey(calendarOutboxId)))
                .isEqualTo(1);

        runtime(
                fixture.recipientContext(),
                () -> notifications.process(
                        fixture.recipientContext().actorId()));
        assertThat(notificationCount(
                        fixture,
                        CalendarParticipationNotificationMessageFactory
                                .deliveryKey(calendarOutboxId)))
                .isEqualTo(1);

        runtime(
                fixture.recipientContext(),
                () -> {
                    NotificationOutboxService.ClaimedNotification claim =
                            notificationOutbox
                                    .claimDue(
                                            fixture.recipientContext()
                                                    .actorId())
                                    .getFirst();
                    assertThat(notificationOutbox.markSent(
                                    claim.id(),
                                    claim.claimToken()))
                            .isTrue();
                    return null;
                });
        runtime(
                fixture.recipientContext(),
                () -> notifications.process(
                        fixture.recipientContext().actorId()));

        assertThat(calendarDeliveryStatus(
                        fixture, calendarOutboxId))
                .isEqualTo("SENT");
        assertThat(receiptDeliveryStatus(
                        fixture, calendarOutboxId))
                .isEqualTo("SENT");
        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM calendar_personal_projection_snapshot
                                WHERE plan_id = ?
                                """,
                                Long.class,
                                fixture.planId())))
                .isEqualTo(snapshotsBeforeDelivery);
    }

    @Test
    void terminalProviderFailureRemainsVisibleOnCalendarReceipt() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture,
                fixture.recipientContext(),
                "notification-failure-viewer");
        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "notification-failure-committed",
                0);
        runtime(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));
        runtime(
                fixture.ownerContext(),
                () -> {
                    CalendarTimeNodeEntity node = nodes
                            .findByPlanIdAndNodeKeyAndWorkspaceIdAndCreatedByUserId(
                                    fixture.planId(),
                                    "boarding",
                                    fixture.ownerContext().workspaceId(),
                                    fixture.ownerContext().actorId())
                            .orElseThrow();
                    Instant changedAt = START.plusSeconds(1);
                    node.reviseAbsolute(
                            START.plusSeconds(600), 1, changedAt);
                    nodes.saveAndFlush(node);
                    sourceMutationSignals.recordOwnerGeneral(
                            node,
                            CalendarSourceMutationSignalService.MutationKind
                                    .NODE_TIME,
                            changedAt);
                    return null;
                });
        runtime(
                fixture.recipientContext(),
                () -> personalProjections.process(
                        fixture.recipientContext().actorId()));
        UUID calendarOutboxId = runtime(
                fixture.recipientContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT id
                        FROM calendar_participation_outbox
                        WHERE plan_id = ?
                          AND event_type =
                              'REVIEW_REQUIRED_UPDATE'
                        """,
                        UUID.class,
                        fixture.planId()));
        String deliveryKey =
                CalendarParticipationNotificationMessageFactory
                        .deliveryKey(calendarOutboxId);

        runtime(
                fixture.recipientContext(),
                () -> notifications.process(
                        fixture.recipientContext().actorId()));
        runtime(
                fixture.recipientContext(),
                () -> {
                    jdbc.update(
                            """
                            UPDATE notification_outbox
                            SET status = 'DEAD_LETTER',
                                attempt_count = 1,
                                last_error = 'PROVIDER_REJECTED',
                                title = NULL, message = NULL,
                                terminal_at = CURRENT_TIMESTAMP
                            WHERE delivery_key = ?
                            """,
                            deliveryKey);
                    return null;
                });
        runtime(
                fixture.recipientContext(),
                () -> notifications.process(
                        fixture.recipientContext().actorId()));

        assertThat(calendarDeliveryStatus(
                        fixture, calendarOutboxId))
                .isEqualTo("FAILED");
        assertThat(receiptDeliveryStatus(
                        fixture, calendarOutboxId))
                .isEqualTo("FAILED");
        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT last_delivery_failure
                                FROM calendar_participation_outbox
                                WHERE id = ?
                                """,
                                String.class,
                                calendarOutboxId)))
                .isEqualTo("PROVIDER_REJECTED");
    }

    private String calendarDeliveryStatus(
            Fixture fixture, UUID outboxId) {
        return runtime(
                fixture.recipientContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT delivery_status
                        FROM calendar_participation_outbox
                        WHERE id = ?
                        """,
                        String.class,
                        outboxId));
    }

    private String receiptDeliveryStatus(
            Fixture fixture, UUID outboxId) {
        return runtime(
                fixture.recipientContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT receipt.delivery_status
                        FROM calendar_authoritative_recipient_receipt receipt
                        JOIN calendar_participation_outbox outbox
                          ON outbox.receipt_id = receipt.id
                        WHERE outbox.id = ?
                        """,
                        String.class,
                        outboxId));
    }

    private long notificationCount(
            Fixture fixture, String deliveryKey) {
        return runtime(
                fixture.recipientContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM notification_outbox
                        WHERE delivery_key = ?
                        """,
                        Long.class,
                        deliveryKey));
    }
}
