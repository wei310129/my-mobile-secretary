package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareView;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class CalendarOwnershipTransferIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarOwnershipTransferService ownershipTransfers;
    @Autowired private CalendarRegistrationAccess registrationAccess;
    @Autowired private CalendarPlanLifecycleService planLifecycle;
    @Autowired private ApplicationEventPublisher events;

    @Test
    void targetAcceptanceAtomicallyChangesEffectiveOwnerAndPreservesPersonalState() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "transfer-viewer");
        changeParticipation(
                fixture,
                fixture.recipientContext(),
                CalendarParticipationStatus.COMMITTED,
                "transfer-participation",
                0);
        runtime(
                fixture.recipientContext(),
                () -> adoptions.adoptPlan(
                        fixture.planId(), List.of("boarding")));
        long participationBefore = count("calendar_participation");
        long adoptionBefore = count("calendar_adoption");
        long registrationBefore = count("calendar_registration");
        long reminderBefore = count("calendar_reminder_rule");

        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-ownership",
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofHours(4))));
        CalendarOwnershipTransferView offerReplay = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-ownership",
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofHours(4))));

        assertThat(offered.status())
                .isEqualTo(CalendarOwnershipTransferStatus.OFFERED);
        assertThat(offerReplay).isEqualTo(offered);
        assertThat(effectiveOwner(fixture))
                .isEqualTo(fixture.ownerContext().actorId());

        CalendarOwnershipTransferView accepted = runtime(
                fixture.peerContext(),
                () -> ownershipTransfers.accept(
                        new CalendarOwnershipTransferAcceptance(
                                "accept-ownership",
                                offered.id(),
                                offered.revision(),
                                1)));
        CalendarOwnershipTransferView acceptReplay = runtime(
                fixture.peerContext(),
                () -> ownershipTransfers.accept(
                        new CalendarOwnershipTransferAcceptance(
                                "accept-ownership",
                                offered.id(),
                                offered.revision(),
                                1)));

        assertThat(accepted.status())
                .isEqualTo(CalendarOwnershipTransferStatus.ACCEPTED);
        assertThat(acceptReplay).isEqualTo(accepted);
        assertThat(effectiveOwner(fixture))
                .isEqualTo(fixture.peerContext().actorId());
        assertThat(count("calendar_ownership_transfer")).isOne();
        assertThat(count("calendar_participation"))
                .isEqualTo(participationBefore);
        assertThat(count("calendar_adoption")).isEqualTo(adoptionBefore);
        assertThat(count("calendar_registration"))
                .isEqualTo(registrationBefore);
        assertThat(count("calendar_reminder_rule"))
                .isEqualTo(reminderBefore);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_outbox
                        WHERE transfer_id = ?
                          AND event_type =
                            'OWNERSHIP_TRANSFER_ACCEPTED'
                        """,
                        Long.class,
                        offered.id()))
                .isEqualTo(2);
    }

    @Test
    void nonTargetCannotAcceptAndOwnershipRemainsUnchanged() {
        Fixture fixture = fixture();
        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-secure-ownership",
                                fixture.planId(),
                                fixture.recipientContext().actorId(),
                                1,
                                Duration.ofHours(2))));

        assertThatThrownBy(() -> runtime(
                        fixture.peerContext(),
                        () -> ownershipTransfers.accept(
                                new CalendarOwnershipTransferAcceptance(
                                        "accept-as-non-target",
                                        offered.id(),
                                        offered.revision(),
                                        1))))
                .isInstanceOfAny(
                        SecurityException.class,
                        BusinessException.class,
                        NotFoundException.class);

        assertThat(effectiveOwner(fixture))
                .isEqualTo(fixture.ownerContext().actorId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT transfer_status
                        FROM calendar_ownership_transfer
                        WHERE id = ?
                        """,
                        String.class,
                        offered.id()))
                .isEqualTo("OFFERED");
    }

    @Test
    void inactiveTargetIsRejectedWithoutTransferOrOutbox() {
        Fixture fixture = fixture();
        jdbc.update(
                "UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?",
                fixture.peerContext().actorId());

        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> ownershipTransfers.offer(
                                new CalendarOwnershipTransferOffer(
                                        "offer-inactive-target",
                                        fixture.planId(),
                                        fixture.peerContext().actorId(),
                                        1,
                                        Duration.ofHours(2)))))
                .isInstanceOf(BusinessException.class);

        assertThat(count("calendar_ownership_transfer")).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_outbox
                        WHERE event_type =
                            'OWNERSHIP_TRANSFER_OFFERED'
                        """,
                        Long.class))
                .isZero();
    }

    @Test
    void ownerCancellationIsIdempotentAndCannotBeAccepted() {
        Fixture fixture = fixture();
        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-cancelable-ownership",
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofHours(2))));

        assertThatThrownBy(() -> runtime(
                        fixture.peerContext(),
                        () -> ownershipTransfers.cancel(
                                new CalendarOwnershipTransferCancellation(
                                        "target-cannot-cancel",
                                        offered.id(),
                                        offered.revision(),
                                        1))))
                .isInstanceOf(SecurityException.class);

        CalendarOwnershipTransferView canceled = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.cancel(
                        new CalendarOwnershipTransferCancellation(
                                "cancel-ownership",
                                offered.id(),
                                offered.revision(),
                                1)));
        CalendarOwnershipTransferView replay = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.cancel(
                        new CalendarOwnershipTransferCancellation(
                                "cancel-ownership",
                                offered.id(),
                                offered.revision(),
                                1)));

        assertThat(canceled.status())
                .isEqualTo(CalendarOwnershipTransferStatus.CANCELED);
        assertThat(replay).isEqualTo(canceled);
        assertThat(effectiveOwner(fixture))
                .isEqualTo(fixture.ownerContext().actorId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_outbox
                        WHERE transfer_id = ?
                          AND event_type =
                            'OWNERSHIP_TRANSFER_CANCELED'
                        """,
                        Long.class,
                        offered.id()))
                .isOne();
        assertThatThrownBy(() -> runtime(
                        fixture.peerContext(),
                        () -> ownershipTransfers.accept(
                                new CalendarOwnershipTransferAcceptance(
                                        "accept-canceled-ownership",
                                        offered.id(),
                                        canceled.revision(),
                                        1))))
                .isInstanceOfAny(BusinessException.class, NotFoundException.class);
    }

    @Test
    void expiredAcceptanceConvergesStatusAndCanBeReoffered() {
        Fixture fixture = fixture();
        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-expiring-ownership",
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofMinutes(1))));
        CalendarOwnershipTransferService expiredClockService =
                new CalendarOwnershipTransferService(
                        registrationAccess,
                        jdbc,
                        Clock.fixed(offered.expiresAt(), ZoneOffset.UTC),
                        events);

        CalendarOwnershipTransferView expired = runtime(
                fixture.peerContext(),
                () -> expiredClockService.accept(
                        new CalendarOwnershipTransferAcceptance(
                                "accept-expired-ownership",
                                offered.id(),
                                offered.revision(),
                                1)));
        CalendarOwnershipTransferView replay = runtime(
                fixture.peerContext(),
                () -> expiredClockService.accept(
                        new CalendarOwnershipTransferAcceptance(
                                "accept-expired-ownership",
                                offered.id(),
                                offered.revision(),
                                1)));

        assertThat(expired.status())
                .isEqualTo(CalendarOwnershipTransferStatus.EXPIRED);
        assertThat(replay).isEqualTo(expired);
        assertThat(effectiveOwner(fixture))
                .isEqualTo(fixture.ownerContext().actorId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_registration_outbox
                        WHERE transfer_id = ?
                          AND event_type =
                            'OWNERSHIP_TRANSFER_EXPIRED'
                        """,
                        Long.class,
                        offered.id()))
                .isEqualTo(2);

        CalendarOwnershipTransferView replacement = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "replacement-ownership-offer",
                                fixture.planId(),
                                fixture.recipientContext().actorId(),
                                1,
                                Duration.ofHours(2))));
        assertThat(replacement.status())
                .isEqualTo(CalendarOwnershipTransferStatus.OFFERED);
    }

    @Test
    void runtimeRoleCannotBypassTwoPhaseAtomicTransfer() {
        Fixture fixture = fixture();
        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-for-direct-tamper",
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofHours(2))));

        int directOwnerSwitch = runtime(
                fixture.ownerContext(),
                () -> jdbc.update(
                        """
                        UPDATE calendar_plan_ownership
                        SET owner_user_id = ?,
                            ownership_revision =
                                ownership_revision + 1,
                            updated_at =
                                updated_at + interval '1 millisecond'
                        WHERE plan_id = ?
                        """,
                        fixture.peerContext().actorId(),
                        fixture.planId()));
        assertThat(directOwnerSwitch).isZero();

        assertThatThrownBy(() -> runtime(
                        fixture.peerContext(),
                        () -> {
                            jdbc.update(
                                    """
                                    UPDATE calendar_plan_ownership
                                    SET owner_user_id = ?,
                                        ownership_revision =
                                            ownership_revision + 1,
                                        updated_at =
                                            updated_at
                                            + interval '1 millisecond'
                                    WHERE plan_id = ?
                                    """,
                                    fixture.peerContext().actorId(),
                                    fixture.planId());
                            return 1;
                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(effectiveOwner(fixture))
                .isEqualTo(fixture.ownerContext().actorId());

        assertThatThrownBy(() -> runtime(
                        fixture.peerContext(),
                        () -> {
                            jdbc.update(
                                    """
                                    UPDATE calendar_ownership_transfer
                                    SET transfer_status = 'ACCEPTED',
                                        transfer_revision =
                                            transfer_revision + 1,
                                        accepted_at =
                                            updated_at
                                            + interval '1 millisecond',
                                        updated_at =
                                            updated_at
                                            + interval '1 millisecond'
                                    WHERE id = ?
                                    """,
                                    offered.id());
                            return 1;
                        }))
                .isInstanceOf(RuntimeException.class);
        assertThat(effectiveOwner(fixture))
                .isEqualTo(fixture.ownerContext().actorId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT transfer_status
                        FROM calendar_ownership_transfer
                        WHERE id = ?
                        """,
                        String.class,
                        offered.id()))
                .isEqualTo("OFFERED");
    }

    @Test
    void activeOwnerAndOfferedTargetCannotLeaveUntilOfferIsCanceled() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> removeMember(fixture.ownerContext()))
                .isInstanceOf(DataIntegrityViolationException.class);

        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-membership-guard",
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofHours(2))));

        assertThatThrownBy(() -> removeMember(fixture.peerContext()))
                .isInstanceOf(DataIntegrityViolationException.class);

        runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.cancel(
                        new CalendarOwnershipTransferCancellation(
                                "cancel-membership-guard",
                                offered.id(),
                                offered.revision(),
                                1)));

        assertThat(removeMember(fixture.peerContext())).isOne();
    }

    @Test
    void acceptedOwnerControlsPlanAndShareUntilArchiveWithoutChangingSourceOwner() {
        Fixture fixture = fixture();
        CalendarOwnershipTransferView offered = runtime(
                fixture.ownerContext(),
                () -> ownershipTransfers.offer(
                        new CalendarOwnershipTransferOffer(
                                "offer-effective-owner-authority",
                                fixture.planId(),
                                fixture.peerContext().actorId(),
                                1,
                                Duration.ofHours(2))));
        runtime(
                fixture.peerContext(),
                () -> ownershipTransfers.accept(
                        new CalendarOwnershipTransferAcceptance(
                                "accept-effective-owner-authority",
                                offered.id(),
                                offered.revision(),
                                1)));

        assertThat(removeMember(fixture.ownerContext())).isOne();
        assertThatThrownBy(() -> removeMember(fixture.peerContext()))
                .isInstanceOf(DataIntegrityViolationException.class);

        CalendarShareView share = runtime(
                fixture.peerContext(),
                () -> shares.createViewerShare(
                        "new-owner-share",
                        fixture.planId(),
                        fixture.recipientContext().actorId(),
                        1));
        assertThatThrownBy(() -> runtime(
                        fixture.ownerContext(),
                        () -> shares.createViewerShare(
                                "former-owner-share",
                                fixture.planId(),
                                fixture.adminContext().actorId(),
                                1)))
                .isInstanceOfAny(
                        SecurityException.class,
                        BusinessException.class,
                        NotFoundException.class);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT created_by_user_id
                        FROM calendar_share
                        WHERE id = ?
                        """,
                        java.util.UUID.class,
                        share.id()))
                .isEqualTo(fixture.ownerContext().actorId());
        assertThat(jdbc.queryForObject(
                        """
                        SELECT created_by_user_id
                        FROM calendar_plan
                        WHERE id = ?
                        """,
                        java.util.UUID.class,
                        fixture.planId()))
                .isEqualTo(fixture.ownerContext().actorId());

        long lifecycleRevision =
                jdbc.queryForObject(
                                "SELECT version FROM calendar_plan WHERE id = ?",
                                Long.class,
                                fixture.planId())
                        + 1;
        runtime(
                fixture.peerContext(),
                () -> planLifecycle.archive(fixture.planId(), lifecycleRevision));

        assertThat(removeMember(fixture.peerContext())).isOne();
    }

    private java.util.UUID effectiveOwner(Fixture fixture) {
        return jdbc.queryForObject(
                """
                SELECT owner_user_id
                FROM calendar_plan_ownership
                WHERE plan_id = ?
                """,
                java.util.UUID.class,
                fixture.planId());
    }

    private int removeMember(
            com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext
                    member) {
        return jdbc.update(
                """
                DELETE FROM workspace_member
                WHERE workspace_id = ? AND user_id = ?
                """,
                member.workspaceId(),
                member.actorId());
    }
}
