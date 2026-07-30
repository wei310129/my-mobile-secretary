package com.aproject.aidriven.mymobilesecretary.booking.execution;

import com.aproject.aidriven.mymobilesecretary.booking.domain.BookingExecutionState;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderCapability;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import com.aproject.aidriven.mymobilesecretary.booking.domain.PurchaseAuthorization;
import com.aproject.aidriven.mymobilesecretary.booking.persistence.BookingExecutionStore;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.BookingProvider;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderMutationCommand;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderMutationResult;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderOperationOutcome;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.QuoteRefreshRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ReconciliationRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class FakePurchaseOrchestrator {

    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    private final BookingExecutionStore store;
    private final Clock clock;

    public FakePurchaseOrchestrator(BookingExecutionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public FakePurchaseResult execute(
            FakePurchaseCommand command, BookingProvider provider) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(provider, "provider");
        PurchaseAuthorization authorization = store.loadAuthorization(command.authorizationId())
                .orElseThrow(() -> new IllegalStateException("authorization not found"));
        var storedOffer = store.loadOffer(authorization.offerId())
                .orElseThrow(() -> new IllegalStateException("offer not found"));
        if (authorization.environment() != ProviderEnvironment.FAKE
                || storedOffer.environment() != ProviderEnvironment.FAKE) {
            throw new SecurityException("B4 accepts FAKE environment only");
        }

        var refreshed = provider.refreshQuote(new QuoteRefreshRequest(storedOffer.offerId()));
        if (refreshed.environment() != ProviderEnvironment.FAKE) {
            throw new SecurityException("refreshed quote is not FAKE");
        }
        if (!storedOffer.inventoryIdentity().equals(refreshed.inventoryIdentity())) {
            throw new SecurityException("refreshed inventory is outside authorization");
        }
        authorization.assertAllows(
                refreshed,
                ProviderCapability.BOOK,
                command.workspaceId(),
                command.actorId(),
                clock);

        var claim = store.claimOperation(
                command.planId(), command.operationId(), ProviderCapability.BOOK, CLAIM_LEASE);
        return switch (claim.disposition()) {
            case MAY_DISPATCH -> dispatch(command, provider, authorization, refreshed, claim);
            case NEEDS_RECONCILIATION ->
                reconcile(command, provider, refreshed.provider());
            case LEASE_HELD ->
                snapshot(
                        command.planId(),
                        ProviderOperationOutcome.NEEDS_RECONCILIATION,
                        "operation-lease-held");
            case ALREADY_SETTLED ->
                snapshot(
                        command.planId(),
                        settledOutcome(command.planId(), command.operationId()),
                        "");
        };
    }

    private FakePurchaseResult dispatch(
            FakePurchaseCommand command,
            BookingProvider provider,
            PurchaseAuthorization authorization,
            com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot refreshed,
            BookingExecutionStore.OperationClaim claim) {
        store.markDispatched(claim.attemptId(), claim.claimToken());
        ProviderMutationResult result;
        try {
            result = provider.book(new ProviderMutationCommand(
                    command.operationId(),
                    refreshed,
                    authorization,
                    command.workspaceId(),
                    command.actorId()));
        } catch (RuntimeException unknownAfterDispatch) {
            return snapshot(
                    command.planId(),
                    ProviderOperationOutcome.NEEDS_RECONCILIATION,
                    "provider-result-unknown");
        }
        return settleDispatched(command, claim, result);
    }

    private FakePurchaseResult settleDispatched(
            FakePurchaseCommand command,
            BookingExecutionStore.OperationClaim claim,
            ProviderMutationResult result) {
        return switch (result.outcome()) {
            case SUCCEEDED -> {
                store.recordSuccess(
                        claim.attemptId(),
                        claim.claimToken(),
                        result.order().orElseThrow(
                                () -> new IllegalStateException("successful result has no order")));
                yield snapshot(command.planId(), result.outcome(), result.publicReason());
            }
            case FAILED -> {
                store.recordFailure(
                        claim.attemptId(), claim.claimToken(), safeReason(result.publicReason()));
                yield snapshot(command.planId(), result.outcome(), result.publicReason());
            }
            case NEEDS_RECONCILIATION, NEEDS_USER_ACTION ->
                snapshot(
                        command.planId(),
                        ProviderOperationOutcome.NEEDS_RECONCILIATION,
                        result.publicReason());
        };
    }

    private FakePurchaseResult reconcile(
            FakePurchaseCommand command, BookingProvider provider, String providerName) {
        ProviderMutationResult result = provider.reconcileUnknownResult(
                new ReconciliationRequest(
                        command.operationId(), providerName, ProviderEnvironment.FAKE));
        return switch (result.outcome()) {
            case SUCCEEDED -> {
                store.reconcileSuccess(
                        command.planId(),
                        command.operationId(),
                        result.order().orElseThrow(
                                () -> new IllegalStateException("successful result has no order")));
                yield snapshot(command.planId(), result.outcome(), result.publicReason());
            }
            case FAILED -> {
                store.reconcileFailure(
                        command.planId(), command.operationId(), safeReason(result.publicReason()));
                yield snapshot(command.planId(), result.outcome(), result.publicReason());
            }
            case NEEDS_RECONCILIATION, NEEDS_USER_ACTION ->
                snapshot(
                        command.planId(),
                        ProviderOperationOutcome.NEEDS_RECONCILIATION,
                        result.publicReason());
        };
    }

    private FakePurchaseResult snapshot(
            java.util.UUID planId,
            ProviderOperationOutcome providerOutcome,
            String publicReason) {
        BookingExecutionState state =
                store.loadPlan(planId).orElseThrow(() -> new IllegalStateException("plan not found"))
                        .state();
        boolean partial = state == BookingExecutionState.PARTIALLY_COMPLETED;
        return new FakePurchaseResult(
                state, providerOutcome, partial, partial, publicReason);
    }

    private ProviderOperationOutcome settledOutcome(
            java.util.UUID planId, String operationId) {
        var plan =
                store.loadPlan(planId).orElseThrow(() -> new IllegalStateException("plan not found"));
        String outcome = plan.attempts().stream()
                .filter(attempt -> attempt.operationId().equals(operationId))
                .map(BookingExecutionStore.AttemptView::outcomeStatus)
                .findFirst()
                .orElse("");
        return "SUCCEEDED".equals(outcome)
                ? ProviderOperationOutcome.SUCCEEDED
                : ProviderOperationOutcome.FAILED;
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "provider-declined" : reason;
    }
}
