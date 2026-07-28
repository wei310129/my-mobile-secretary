package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CalendarRegistrationCapacityIntegrationTest
        extends CalendarParticipationIntegrationSupport {

    @Autowired private CalendarRegistrationPolicyService registrationPolicies;
    @Autowired private CalendarRegistrationService registrations;
    @Autowired private CalendarWaitlistService waitlists;
    @Autowired private Clock clock;

    @Test
    void concurrentLastSeatCommitsExactlyOneAndWaitlistsTheOther() throws Exception {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "capacity-viewer-1");
        grantWholePlanViewer(
                fixture, fixture.peerContext(), "capacity-viewer-2");
        configurePolicy(fixture, 1, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<CalendarRegistrationView> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return runtime(
                        fixture.recipientContext(),
                        () -> registrations.join(new CalendarRegistrationJoinCommand(
                                "join-last-seat-a",
                                fixture.planId(),
                                planScope(fixture))));
            });
            Future<CalendarRegistrationView> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return runtime(
                        fixture.peerContext(),
                        () -> registrations.join(new CalendarRegistrationJoinCommand(
                                "join-last-seat-b",
                                fixture.planId(),
                                planScope(fixture))));
            });
            ready.await();
            start.countDown();

            assertThat(List.of(first.get().state(), second.get().state()))
                    .containsExactlyInAnyOrder(
                            CalendarRegistrationState.COMMITTED,
                            CalendarRegistrationState.WAITLISTED);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                        """
                        SELECT committed_count
                        FROM calendar_capacity_bucket
                        WHERE plan_id = ?
                        """,
                        Integer.class,
                        fixture.planId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT waitlisted_count
                        FROM calendar_capacity_bucket
                        WHERE plan_id = ?
                        """,
                        Integer.class,
                        fixture.planId()))
                .isEqualTo(1);
    }

    @Test
    void lateWaitlistPolicyNeverConsumesCapacity() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "late-viewer");
        configurePolicy(
                fixture,
                2,
                Instant.now(clock).minus(Duration.ofMinutes(1)));

        CalendarRegistrationView result = runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "late-waitlist",
                        fixture.planId(),
                        planScope(fixture))));

        assertThat(result.state())
                .isEqualTo(CalendarRegistrationState.WAITLISTED);
        assertThat(result.waitlistPosition()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT committed_count
                        FROM calendar_capacity_bucket
                        WHERE plan_id = ?
                        """,
                        Integer.class,
                        fixture.planId()))
                .isZero();
    }

    @Test
    void fifoOfferCommitsOnlyAfterActorAcceptsAndReplayIsStable() {
        Fixture fixture = fixture();
        grantWholePlanViewer(
                fixture, fixture.recipientContext(), "fifo-viewer-1");
        grantWholePlanViewer(
                fixture, fixture.peerContext(), "fifo-viewer-2");
        configurePolicy(fixture, 1, null);
        runtime(
                fixture.recipientContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "fifo-join-committed",
                        fixture.planId(),
                        planScope(fixture))));
        CalendarRegistrationView waiting = runtime(
                fixture.peerContext(),
                () -> registrations.join(new CalendarRegistrationJoinCommand(
                        "fifo-join-waiting",
                        fixture.planId(),
                        planScope(fixture))));
        expandCapacity(fixture, 2);

        CalendarWaitlistOfferView offer = inContext(
                fixture.ownerContext(),
                () -> waitlists.offerNext(
                        "offer-next",
                        fixture.planId(),
                        planScope(fixture)));
        assertThat(runtime(
                                fixture.peerContext(),
                                () -> registrations.current(
                                        fixture.planId(),
                                        planScope(fixture)))
                        .orElseThrow()
                        .state())
                .isEqualTo(CalendarRegistrationState.WAITLISTED);

        CalendarRegistrationView accepted = runtime(
                fixture.peerContext(),
                () -> waitlists.accept(new CalendarWaitlistOfferResponse(
                        "accept-offer",
                        offer.id(),
                        offer.revision())));
        CalendarRegistrationView replay = runtime(
                fixture.peerContext(),
                () -> waitlists.accept(new CalendarWaitlistOfferResponse(
                        "accept-offer",
                        offer.id(),
                        offer.revision())));

        assertThat(accepted.state())
                .isEqualTo(CalendarRegistrationState.COMMITTED);
        assertThat(replay).isEqualTo(accepted);
        assertThat(waiting.waitlistPosition()).isEqualTo(1);
        assertThat(count("calendar_waitlist_offer")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT committed_count
                        FROM calendar_capacity_bucket
                        WHERE plan_id = ?
                        """,
                        Integer.class,
                        fixture.planId()))
                .isEqualTo(2);
    }

    private void configurePolicy(
            Fixture fixture, int capacity, Instant closesAt) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "capacity-policy-" + fixture.planId(),
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                closesAt == null
                                        ? now.plus(Duration.ofHours(2))
                                        : closesAt,
                                "Asia/Taipei",
                                capacity,
                                closesAt == null
                                        ? CalendarLateJoinPolicy.ALLOW_IF_CAPACITY
                                        : CalendarLateJoinPolicy.WAITLIST_ONLY,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                0)));
    }

    private void expandCapacity(Fixture fixture, int capacity) {
        Instant now = Instant.now(clock);
        inContext(
                fixture.ownerContext(),
                () -> registrationPolicies.configure(
                        new CalendarRegistrationPolicyChange(
                                "expand-capacity-" + fixture.planId(),
                                fixture.planId(),
                                planScope(fixture),
                                now.minus(Duration.ofHours(1)),
                                now.plus(Duration.ofHours(2)),
                                "Asia/Taipei",
                                capacity,
                                CalendarLateJoinPolicy.ALLOW_IF_CAPACITY,
                                true,
                                CalendarWaitlistPromotionMode.AUTO_OFFER,
                                CalendarRegistrationLimitMode.HARD_LIMIT,
                                Duration.ofMinutes(20),
                                CalendarLateNotificationPolicy.IMMEDIATE,
                                false,
                                1)));
    }
}
