package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationOutboxService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarRegistrationNotificationWorkerIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired
    private CalendarRegistrationNotificationProcessor notifications;

    @Autowired
    private NotificationOutboxService notificationOutbox;

    @Autowired
    private CalendarOwnershipTransferService ownershipTransfers;

    @Test
    void stableDeliveryIsRecipientScopedAndOnlySentAfterGenericOutboxCompletes() {
        Fixture fixture = fixture();
        UUID registrationOutboxId = offerToPeer(
                fixture, "registration-notification-stable");
        String deliveryKey =
                CalendarRegistrationNotificationMessageFactory.deliveryKey(
                        registrationOutboxId);

        assertThat(runtime(
                        fixture.ownerContext(),
                        () -> notifications.process(
                                fixture.ownerContext().actorId())))
                .isZero();
        assertThat(notificationCount(
                        fixture, fixture.ownerContext(), deliveryKey))
                .isZero();

        assertThat(runtime(
                        fixture.peerContext(),
                        () -> notifications.process(
                                fixture.peerContext().actorId())))
                .isOne();
        assertThat(registrationDeliveryStatus(
                        fixture, registrationOutboxId))
                .isEqualTo("PENDING");
        assertThat(notificationCount(
                        fixture, fixture.peerContext(), deliveryKey))
                .isOne();

        runtime(
                fixture.peerContext(),
                () -> notifications.process(
                        fixture.peerContext().actorId()));
        assertThat(notificationCount(
                        fixture, fixture.peerContext(), deliveryKey))
                .isOne();

        runtime(
                fixture.peerContext(),
                () -> {
                    NotificationOutboxService.ClaimedNotification claim =
                            notificationOutbox
                                    .claimDue(
                                            fixture.peerContext().actorId())
                                    .getFirst();
                    assertThat(notificationOutbox.markSent(
                                    claim.id(),
                                    claim.claimToken()))
                            .isTrue();
                    return null;
                });
        runtime(
                fixture.peerContext(),
                () -> notifications.process(
                        fixture.peerContext().actorId()));

        assertThat(registrationDeliveryStatus(
                        fixture, registrationOutboxId))
                .isEqualTo("SENT");
        assertThat(notificationCount(
                        fixture, fixture.peerContext(), deliveryKey))
                .isOne();
        assertThat(runtime(
                        fixture.peerContext(),
                        () -> notifications.process(
                                fixture.peerContext().actorId())))
                .isZero();
    }

    @Test
    void terminalProviderFailureIsVisibleAndDoesNotReenqueue() {
        Fixture fixture = fixture();
        UUID registrationOutboxId = offerToPeer(
                fixture, "registration-notification-failure");
        String deliveryKey =
                CalendarRegistrationNotificationMessageFactory.deliveryKey(
                        registrationOutboxId);

        runtime(
                fixture.peerContext(),
                () -> notifications.process(
                        fixture.peerContext().actorId()));
        runtime(
                fixture.peerContext(),
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
                fixture.peerContext(),
                () -> notifications.process(
                        fixture.peerContext().actorId()));

        assertThat(registrationDeliveryStatus(
                        fixture, registrationOutboxId))
                .isEqualTo("FAILED");
        assertThat(runtime(
                        fixture.peerContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT last_delivery_failure
                                FROM calendar_registration_outbox
                                WHERE id = ?
                                """,
                                String.class,
                                registrationOutboxId)))
                .isEqualTo("PROVIDER_REJECTED");
        assertThat(notificationCount(
                        fixture, fixture.peerContext(), deliveryKey))
                .isOne();
        assertThat(runtime(
                        fixture.peerContext(),
                        () -> notifications.process(
                                fixture.peerContext().actorId())))
                .isZero();
    }

    private UUID offerToPeer(Fixture fixture, String requestKey) {
        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                requestKey,
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofHours(2))));
        return runtime(
                fixture.peerContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT id
                        FROM calendar_registration_outbox
                        WHERE transfer_id = ?
                          AND event_type =
                            'OWNERSHIP_TRANSFER_OFFERED'
                          AND recipient_user_id = ?
                        """,
                        UUID.class,
                        offered.id(),
                        fixture.peerContext().actorId()));
    }

    private String registrationDeliveryStatus(
            Fixture fixture, UUID outboxId) {
        return runtime(
                fixture.peerContext(),
                () -> jdbc.queryForObject(
                        """
                        SELECT delivery_status
                        FROM calendar_registration_outbox
                        WHERE id = ?
                        """,
                        String.class,
                        outboxId));
    }

    private long notificationCount(
            Fixture fixture,
            com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext
                    actor,
            String deliveryKey) {
        return runtime(
                actor,
                () -> jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM notification_outbox
                        WHERE workspace_id = ?
                          AND target_user_id = ?
                          AND delivery_key = ?
                        """,
                        Long.class,
                        actor.workspaceId(),
                        actor.actorId(),
                        deliveryKey));
    }
}
