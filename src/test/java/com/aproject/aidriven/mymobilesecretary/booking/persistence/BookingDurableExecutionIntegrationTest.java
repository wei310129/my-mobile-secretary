package com.aproject.aidriven.mymobilesecretary.booking.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.booking.domain.AuthorizationViolation;
import com.aproject.aidriven.mymobilesecretary.booking.domain.BookingExecutionState;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ConfirmationMode;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrderStatus;
import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderCapability;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import com.aproject.aidriven.mymobilesecretary.booking.domain.PurchaseAuthorization;
import com.aproject.aidriven.mymobilesecretary.booking.domain.SubstitutionStrength;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BookingDurableExecutionIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_booking_rls_runtime";
    @Autowired private BookingExecutionStore store;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private Clock clock;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                        WHERE rolname = 'mms_booking_rls_runtime') THEN
                        CREATE ROLE mms_booking_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void persistsOwnedQuoteAuthorizationPlanAttemptOrderAndTerminalResult() {
        Fixture fixture = fixture("complete");

        inContext(fixture.context(), () -> {
            store.saveOffer(fixture.offer());
            store.saveAuthorization(fixture.authorization());
            store.createAuthorizedPlan(
                    fixture.planId(), fixture.authorization().authorizationId(), 1);
            BookingExecutionStore.OperationClaim claim = store.claimOperation(
                    fixture.planId(), "book-flight-1", ProviderCapability.BOOK, Duration.ofMinutes(2));
            assertThat(claim.disposition())
                    .isEqualTo(BookingExecutionStore.DispatchDisposition.MAY_DISPATCH);
            store.markDispatched(claim.attemptId(), claim.claimToken());
            store.recordSuccess(
                    claim.attemptId(), claim.claimToken(), fixture.order());
            return null;
        });

        inContext(fixture.context(), () -> {
            assertThat(store.loadOffer(fixture.offer().offerId()).orElseThrow())
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(fixture.offer());
            assertThat(store.loadAuthorization(fixture.authorization().authorizationId()))
                    .isPresent();
            BookingExecutionStore.PlanView plan = store.loadPlan(fixture.planId()).orElseThrow();
            assertThat(plan.state()).isEqualTo(BookingExecutionState.COMPLETED);
            assertThat(plan.attempts()).hasSize(1);
            assertThat(plan.orders()).containsExactly(fixture.order());
            assertThat(store.claimTerminalResults(10, Duration.ofMinutes(1))).hasSize(1);
            return null;
        });
    }

    @Test
    void recoversExpiredPreDispatchLeaseButNeverResendsAfterDispatch() {
        Fixture fixture = fixture("crash");
        BookingExecutionStore.OperationClaim first = inContext(fixture.context(), () -> {
            store.saveOffer(fixture.offer());
            store.saveAuthorization(fixture.authorization());
            store.createAuthorizedPlan(
                    fixture.planId(), fixture.authorization().authorizationId(), 1);
            return store.claimOperation(
                    fixture.planId(), "book-crash", ProviderCapability.BOOK, Duration.ofMinutes(2));
        });
        jdbc.update(
                "UPDATE booking_attempt SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                first.attemptId());

        BookingExecutionStore.OperationClaim recovered = inContext(
                fixture.context(),
                () -> store.claimOperation(
                        fixture.planId(),
                        "book-crash",
                        ProviderCapability.BOOK,
                        Duration.ofMinutes(2)));
        assertThat(recovered.attemptId()).isEqualTo(first.attemptId());
        assertThat(recovered.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(recovered.disposition())
                .isEqualTo(BookingExecutionStore.DispatchDisposition.MAY_DISPATCH);

        inContext(fixture.context(), () -> {
            store.markDispatched(recovered.attemptId(), recovered.claimToken());
            return null;
        });
        jdbc.update(
                "UPDATE booking_attempt SET lease_until = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                recovered.attemptId());

        BookingExecutionStore.OperationClaim afterSend = inContext(
                fixture.context(),
                () -> store.claimOperation(
                        fixture.planId(),
                        "book-crash",
                        ProviderCapability.BOOK,
                        Duration.ofMinutes(2)));
        assertThat(afterSend.disposition())
                .isEqualTo(BookingExecutionStore.DispatchDisposition.NEEDS_RECONCILIATION);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM booking_attempt WHERE operation_id = 'book-crash'",
                        Long.class))
                .isEqualTo(1L);

        inContext(fixture.context(), () -> {
            store.reconcileSuccess(fixture.planId(), "book-crash", fixture.order());
            assertThat(store.loadPlan(fixture.planId()).orElseThrow().state())
                    .isEqualTo(BookingExecutionState.COMPLETED);
            return null;
        });
    }

    @Test
    void webhookInboxRejectsConflictingReplayAndTerminalOutboxUsesFencedAck() {
        Fixture fixture = fixture("inbox");
        inContext(fixture.context(), () -> {
            assertThat(store.acceptWebhook(
                            "duffel",
                            ProviderEnvironment.FAKE,
                            "event-key-hmac",
                            "payload-sha-256",
                            "order.updated",
                            3))
                    .isEqualTo(BookingExecutionStore.WebhookDisposition.ACCEPTED);
            assertThat(store.acceptWebhook(
                            "duffel",
                            ProviderEnvironment.FAKE,
                            "event-key-hmac",
                            "payload-sha-256",
                            "order.updated",
                            3))
                    .isEqualTo(BookingExecutionStore.WebhookDisposition.DUPLICATE);
            assertThat(store.acceptWebhook(
                            "duffel",
                            ProviderEnvironment.FAKE,
                            "older-event-key-hmac",
                            "older-payload-sha-256",
                            "order.updated",
                            2))
                    .isEqualTo(BookingExecutionStore.WebhookDisposition.OUT_OF_ORDER);
            assertThatThrownBy(() -> store.acceptWebhook(
                            "duffel",
                            ProviderEnvironment.FAKE,
                            "event-key-hmac",
                            "different-payload-sha-256",
                            "order.updated",
                            4))
                    .hasRootCauseInstanceOf(IllegalStateException.class);

            complete(fixture);
            BookingExecutionStore.TerminalClaim claim =
                    store.claimTerminalResults(1, Duration.ofMinutes(1)).getFirst();
            assertThat(store.claimTerminalResults(1, Duration.ofMinutes(1))).isEmpty();
            assertThat(store.acknowledgeTerminal(claim.outboxId(), UUID.randomUUID())).isFalse();
            assertThat(store.acknowledgeTerminal(claim.outboxId(), claim.claimToken())).isTrue();
            assertThat(store.claimTerminalResults(1, Duration.ofMinutes(1))).isEmpty();
            return null;
        });
    }

    @Test
    void concurrentDuplicateOperationCreatesOneAttemptAndOnlyOneDispatchPermission()
            throws Exception {
        Fixture fixture = fixture("concurrent");
        inContext(fixture.context(), () -> {
            store.saveOffer(fixture.offer());
            store.saveAuthorization(fixture.authorization());
            store.createAuthorizedPlan(
                    fixture.planId(), fixture.authorization().authorizationId(), 1);
            return null;
        });
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Supplier<BookingExecutionStore.OperationClaim> claim = () -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return inContext(
                        fixture.context(),
                        () -> store.claimOperation(
                                fixture.planId(),
                                "same-operation",
                                ProviderCapability.BOOK,
                                Duration.ofMinutes(2)));
            };
            Future<BookingExecutionStore.OperationClaim> first = executor.submit(claim::get);
            Future<BookingExecutionStore.OperationClaim> second = executor.submit(claim::get);
            ready.await();
            start.countDown();
            List<BookingExecutionStore.OperationClaim> claims =
                    List.of(first.get(), second.get());

            assertThat(claims)
                    .extracting(BookingExecutionStore.OperationClaim::attemptId)
                    .containsOnly(claims.getFirst().attemptId());
            assertThat(claims)
                    .extracting(BookingExecutionStore.OperationClaim::disposition)
                    .containsExactlyInAnyOrder(
                            BookingExecutionStore.DispatchDisposition.MAY_DISPATCH,
                            BookingExecutionStore.DispatchDisposition.LEASE_HELD);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM booking_attempt WHERE operation_id = 'same-operation'",
                            Long.class))
                    .isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void durableClaimRevalidatesAuthorizationAtTheMutationBoundary() {
        Fixture fixture = fixture("expired-auth");
        inContext(fixture.context(), () -> {
            store.saveOffer(fixture.offer());
            store.saveAuthorization(fixture.authorization());
            store.createAuthorizedPlan(
                    fixture.planId(), fixture.authorization().authorizationId(), 1);
            return null;
        });
        jdbc.update(
                "UPDATE booking_purchase_authorization SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.EPOCH),
                fixture.authorization().authorizationId());

        assertThatThrownBy(() -> inContext(
                        fixture.context(),
                        () -> store.claimOperation(
                                fixture.planId(),
                                "expired-operation",
                                ProviderCapability.BOOK,
                                Duration.ofMinutes(2))))
                .hasRootCauseInstanceOf(AuthorizationViolation.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM booking_attempt WHERE plan_id = ?",
                        Long.class,
                        fixture.planId()))
                .isZero();
    }

    @Test
    void conflictingAuthorizationAndPlanReplaysFailClosedWithoutOverwriting() {
        Fixture fixture = fixture("conflicting-replay");
        inContext(fixture.context(), () -> {
            store.saveOffer(fixture.offer());
            store.saveAuthorization(fixture.authorization());
            return null;
        });
        PurchaseAuthorization conflicting = new PurchaseAuthorization(
                fixture.authorization().authorizationId(),
                fixture.workspaceId(),
                fixture.actorId(),
                fixture.authorization().travellerIds(),
                fixture.offer().offerId(),
                fixture.offer().provider(),
                fixture.offer().environment(),
                new BigDecimal("999.00"),
                fixture.offer().currency(),
                fixture.offer().termsFingerprint(),
                false,
                fixture.authorization().expiresAt(),
                ConfirmationMode.BATCH,
                SubstitutionStrength.EXACT);

        assertThatThrownBy(() -> inContext(
                        fixture.context(),
                        () -> {
                            store.saveAuthorization(conflicting);
                            return null;
                        }))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        inContext(fixture.context(), () -> {
            store.createAuthorizedPlan(
                    fixture.planId(), fixture.authorization().authorizationId(), 1);
            return null;
        });
        assertThatThrownBy(() -> inContext(
                        fixture.context(),
                        () -> {
                            store.createAuthorizedPlan(
                                    fixture.planId(),
                                    fixture.authorization().authorizationId(),
                                    2);
                            return null;
                        }))
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM booking_purchase_authorization WHERE id = ?",
                        Long.class,
                        fixture.authorization().authorizationId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT total_items FROM booking_plan WHERE id = ?",
                        Integer.class,
                        fixture.planId()))
                .isEqualTo(1);
    }

    @Test
    void partialFailurePreservesSuccessfulOrderStopsPlanAndEmitsOneTerminal() {
        Fixture fixture = fixture("partial");
        inContext(fixture.context(), () -> {
            store.saveOffer(fixture.offer());
            store.saveAuthorization(fixture.authorization());
            store.createAuthorizedPlan(
                    fixture.planId(), fixture.authorization().authorizationId(), 2);
            BookingExecutionStore.OperationClaim success = store.claimOperation(
                    fixture.planId(),
                    "partial-success",
                    ProviderCapability.BOOK,
                    Duration.ofMinutes(2));
            store.markDispatched(success.attemptId(), success.claimToken());
            store.recordSuccess(success.attemptId(), success.claimToken(), fixture.order());

            BookingExecutionStore.OperationClaim failure = store.claimOperation(
                    fixture.planId(),
                    "partial-failure",
                    ProviderCapability.BOOK,
                    Duration.ofMinutes(2));
            store.markDispatched(failure.attemptId(), failure.claimToken());
            store.recordFailure(
                    failure.attemptId(), failure.claimToken(), "provider-declined");

            BookingExecutionStore.PlanView plan = store.loadPlan(fixture.planId()).orElseThrow();
            assertThat(plan.state()).isEqualTo(BookingExecutionState.PARTIALLY_COMPLETED);
            assertThat(plan.orders()).containsExactly(fixture.order());
            assertThat(plan.attempts()).hasSize(2);
            assertThatThrownBy(() -> store.claimOperation(
                                    fixture.planId(),
                                    "must-not-continue",
                                    ProviderCapability.BOOK,
                                    Duration.ofMinutes(2)))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            return null;
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM booking_execution_outbox WHERE plan_id = ?",
                        Long.class,
                        fixture.planId()))
                .isEqualTo(1L);
    }

    @Test
    void compositeOwnershipAndRlsHideEveryBookingRowFromPeerOutsiderAndSystem() {
        Fixture owner = fixture("owner");
        UUID peerId = UUID.randomUUID();
        seedPeer(peerId, owner.actorId(), owner.workspaceId(), "peer");
        Fixture outsider = fixture("outsider");
        inContext(owner.context(), () -> {
            complete(owner);
            store.acceptWebhook(
                    "duffel",
                    ProviderEnvironment.FAKE,
                    "owner-event",
                    "owner-payload",
                    "order.updated",
                    1);
            return null;
        });

        assertThat(runtime(owner.context(), this::bookingCounts))
                .allMatch(count -> count == 1L);
        assertThat(runtime(context(peerId, owner.workspaceId()), this::bookingCounts))
                .allMatch(count -> count == 0L);
        assertThat(runtime(outsider.context(), this::bookingCounts))
                .allMatch(count -> count == 0L);
        assertThat(runtime(WorkspaceContext.system(), this::bookingCounts))
                .allMatch(count -> count == 0L);
        assertThatThrownBy(() -> runtime(context(peerId, owner.workspaceId()), () -> jdbc.update(
                        """
                        INSERT INTO booking_plan (
                            id, authorization_id, total_items, state, version,
                            created_at, updated_at, workspace_id, created_by_user_id)
                        VALUES (?, ?, 1, 'AUTHORIZED', 0, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        owner.authorization().authorizationId(),
                        Timestamp.from(clock.instant()),
                        Timestamp.from(clock.instant()),
                        owner.workspaceId(),
                        owner.actorId())))
                .isInstanceOf(DataAccessException.class);
    }

    private void complete(Fixture fixture) {
        store.saveOffer(fixture.offer());
        store.saveAuthorization(fixture.authorization());
        store.createAuthorizedPlan(fixture.planId(), fixture.authorization().authorizationId(), 1);
        BookingExecutionStore.OperationClaim claim = store.claimOperation(
                fixture.planId(),
                "book-" + fixture.label(),
                ProviderCapability.BOOK,
                Duration.ofMinutes(2));
        store.markDispatched(claim.attemptId(), claim.claimToken());
        store.recordSuccess(claim.attemptId(), claim.claimToken(), fixture.order());
    }

    private java.util.List<Long> bookingCounts() {
        return java.util.List.of(
                count("booking_offer_snapshot"),
                count("booking_purchase_authorization"),
                count("booking_plan"),
                count("external_booking_order"),
                count("booking_attempt"),
                count("booking_webhook_inbox"),
                count("booking_execution_outbox"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private Fixture fixture(String label) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID travellerId = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        seed(actorId, workspaceId, label);
        OfferSnapshot offer = new OfferSnapshot(
                offerId,
                "duffel",
                ProviderEnvironment.FAKE,
                "inventory-" + label,
                now,
                now.plus(Duration.ofHours(1)),
                new BigDecimal("123.45"),
                Currency.getInstance("USD"),
                Set.of(travellerId),
                "terms-sha-256-" + label,
                false,
                true,
                Set.of(ProviderCapability.BOOK));
        PurchaseAuthorization authorization = new PurchaseAuthorization(
                authorizationId,
                workspaceId,
                actorId,
                Set.of(travellerId),
                offerId,
                "duffel",
                ProviderEnvironment.FAKE,
                new BigDecimal("123.45"),
                Currency.getInstance("USD"),
                "terms-sha-256-" + label,
                false,
                now.plus(Duration.ofMinutes(30)),
                ConfirmationMode.BATCH,
                SubstitutionStrength.EXACT);
        ExternalBookingOrder order = new ExternalBookingOrder(
                UUID.randomUUID(),
                "duffel",
                ProviderEnvironment.FAKE,
                "masked-reference-" + label,
                ExternalBookingOrderStatus.CONFIRMED,
                now.plusSeconds(5));
        return new Fixture(
                label,
                actorId,
                workspaceId,
                offer,
                authorization,
                planId,
                order);
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }

    private void seedPeer(UUID actorId, UUID ownerId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspaceId,
                actorId,
                ownerId);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private record Fixture(
            String label,
            UUID actorId,
            UUID workspaceId,
            OfferSnapshot offer,
            PurchaseAuthorization authorization,
            UUID planId,
            ExternalBookingOrder order) {

        WorkspaceContext context() {
            return BookingDurableExecutionIntegrationTest.context(actorId, workspaceId);
        }
    }
}
