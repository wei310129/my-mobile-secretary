package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarWaitlistExpiryIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationAccess registrationAccess;
    @Autowired private CalendarParticipationService participations;
    @Autowired private CalendarRegistrationService registrations;
    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarWaitlistService waitlists;
    @Autowired private Clock clock;

    @Test
    void exactExpiryDeclinesOfferedActorAndAutoOffersNextOnce() {
        Fixture fixture = fixture();
        WorkspaceContext third = addActor(fixture, "expiry third");
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "expiry-viewer-1");
        grantWholePlanViewer(
                fixture, fixture.peerContext(), "expiry-viewer-2");
        grantWholePlanViewer(fixture, third, "expiry-viewer-3");
        configure(fixture, 1, CalendarWaitlistPromotionMode.AUTO_OFFER, 0);
        join(fixture, fixture.recipientContext(), "expiry-committed");
        join(fixture, fixture.peerContext(), "expiry-first");
        join(fixture, third, "expiry-second");
        configure(fixture, 2, CalendarWaitlistPromotionMode.AUTO_OFFER, 1);

        CalendarWaitlistOfferView offered = inContext(
                fixture.ownerContext(),
                () -> waitlists.offerNext(
                        "expiry-offer",
                        fixture.planId(),
                        planScope(fixture)));
        CalendarWaitlistService atExpiry = waitlistsAt(offered.expiresAt());

        CalendarWaitlistExpirySweepResult result = inContext(
                fixture.ownerContext(),
                () -> atExpiry.sweepExpired(
                        "expiry-sweep",
                        fixture.planId(),
                        planScope(fixture)));
        CalendarWaitlistExpirySweepResult replay = inContext(
                fixture.ownerContext(),
                () -> atExpiry.sweepExpired(
                        "expiry-sweep",
                        fixture.planId(),
                        planScope(fixture)));

        assertThat(replay).isEqualTo(result);
        assertThat(result.expiredOfferId()).isEqualTo(offered.id());
        assertThat(result.expiredOfferRevision()).isEqualTo(2);
        assertThat(result.nextOffer()).isNotNull();
        assertThat(result.nextOffer().id()).isNotEqualTo(offered.id());
        assertThat(offerStatus(offered.id())).isEqualTo("EXPIRED");
        assertThat(offerStatus(result.nextOffer().id()))
                .isEqualTo("OFFERED");
        assertThat(offerActor(result.nextOffer().id()))
                .isEqualTo(third.actorId());
        assertThat(registrationState(
                        fixture, fixture.peerContext()))
                .isEqualTo("DECLINED");
        assertThat(registrationState(fixture, third))
                .isEqualTo("WAITLISTED");
        assertThat(bucketCount(fixture, "committed_count"))
                .isEqualTo(1);
        assertThat(bucketCount(fixture, "waitlisted_count"))
                .isEqualTo(1);
        assertThat(historyCount(offered.id())).isEqualTo(1);
        assertThat(outboxCount(offered.id())).isEqualTo(1);
        assertThat(offeredOutboxCount(result.nextOffer().id()))
                .isEqualTo(1);
        assertThat(receiptCount("expiry-sweep")).isEqualTo(1);
    }

    @Test
    void manualModeExpiresCurrentOfferWithoutOfferingNext() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "manual-viewer-1");
        grantWholePlanViewer(
                fixture, fixture.peerContext(), "manual-viewer-2");
        configure(
                fixture,
                1,
                CalendarWaitlistPromotionMode.MANUAL_OFFER,
                0);
        join(fixture, fixture.recipientContext(), "manual-committed");
        join(fixture, fixture.peerContext(), "manual-waiting");
        configure(
                fixture,
                2,
                CalendarWaitlistPromotionMode.MANUAL_OFFER,
                1);
        CalendarWaitlistOfferView offered = inContext(
                fixture.ownerContext(),
                () -> waitlists.offerNext(
                        "manual-offer",
                        fixture.planId(),
                        planScope(fixture)));

        CalendarWaitlistExpirySweepResult result = inContext(
                fixture.ownerContext(),
                () -> waitlistsAt(offered.expiresAt())
                        .sweepExpired(
                                "manual-sweep",
                                fixture.planId(),
                                planScope(fixture)));

        assertThat(result.nextOffer()).isNull();
        assertThat(offerStatus(offered.id())).isEqualTo("EXPIRED");
        assertThat(activeOfferCount(fixture)).isZero();
        assertThat(bucketCount(fixture, "waitlisted_count"))
                .isZero();
        assertThat(registrationState(
                        fixture, fixture.peerContext()))
                .isEqualTo("DECLINED");
    }

    private CalendarWaitlistService waitlistsAt(Instant now) {
        return new CalendarWaitlistService(
                registrationAccess,
                participations,
                registrations,
                jdbc,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private void configure(
            Fixture fixture,
            int capacity,
            CalendarWaitlistPromotionMode promotionMode,
            long expectedRevision) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "expiry-policy-"
                                        + fixture.planId()
                                        + "-"
                                        + expectedRevision,
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                now.plus(Duration.ofHours(2)),
                                "Asia/Taipei",
                                capacity,
                                CalendarLateJoinPolicy
                                        .ALLOW_IF_CAPACITY,
                                true,
                                promotionMode,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                expectedRevision)));
    }

    private CalendarRegistrationView join(
            Fixture fixture,
            WorkspaceContext actor,
            String requestId) {
        return runtime(
                actor,
                () -> registrations.join(
                        new CalendarRegistrationJoinCommand(
                                requestId,
                                fixture.planId(),
                                planScope(fixture))));
    }

    private String offerStatus(java.util.UUID offerId) {
        return jdbc.queryForObject(
                """
                SELECT offer_status
                FROM calendar_waitlist_offer
                WHERE id = ?
                """,
                String.class,
                offerId);
    }

    private java.util.UUID offerActor(java.util.UUID offerId) {
        return jdbc.queryForObject(
                """
                SELECT created_by_user_id
                FROM calendar_waitlist_offer
                WHERE id = ?
                """,
                java.util.UUID.class,
                offerId);
    }

    private String registrationState(
            Fixture fixture, WorkspaceContext actor) {
        return jdbc.queryForObject(
                """
                SELECT registration_state
                FROM calendar_registration
                WHERE plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                String.class,
                fixture.planId(),
                fixture.ownerContext().workspaceId(),
                actor.actorId());
    }

    private int bucketCount(Fixture fixture, String column) {
        return jdbc.queryForObject(
                "SELECT "
                        + column
                        + " FROM calendar_capacity_bucket"
                        + " WHERE plan_id = ?",
                Integer.class,
                fixture.planId());
    }

    private long activeOfferCount(Fixture fixture) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_waitlist_offer
                WHERE plan_id = ? AND offer_status = 'OFFERED'
                """,
                Long.class,
                fixture.planId());
    }

    private long historyCount(java.util.UUID offerId) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_history history
                JOIN calendar_waitlist_offer offer
                  ON offer.registration_id = history.registration_id
                WHERE offer.id = ?
                  AND history.event_type = 'OFFER_EXPIRED'
                """,
                Long.class,
                offerId);
    }

    private long outboxCount(java.util.UUID offerId) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_outbox
                WHERE waitlist_offer_id = ?
                  AND event_type = 'WAITLIST_OFFER_EXPIRED'
                """,
                Long.class,
                offerId);
    }

    private long offeredOutboxCount(java.util.UUID offerId) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_outbox
                WHERE waitlist_offer_id = ?
                  AND event_type = 'WAITLIST_OFFERED'
                """,
                Long.class,
                offerId);
    }

    private long receiptCount(String requestId) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration_request_receipt
                WHERE request_kind = 'WAITLIST_OFFER'
                  AND operation_request_hash = ?
                  AND result_state = 'EXPIRED'
                """,
                Long.class,
                CalendarParticipationAccess.hash(requestId));
    }
}
