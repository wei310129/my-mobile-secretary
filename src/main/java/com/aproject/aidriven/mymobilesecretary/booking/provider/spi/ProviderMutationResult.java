package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import java.util.Objects;
import java.util.Optional;

public record ProviderMutationResult(
        ProviderOperationOutcome outcome,
        Optional<ExternalBookingOrder> order,
        String publicReason) {

    public ProviderMutationResult {
        Objects.requireNonNull(outcome, "outcome");
        order = Objects.requireNonNull(order, "order");
        publicReason = publicReason == null ? "" : publicReason;
    }

    public static ProviderMutationResult succeeded(ExternalBookingOrder order) {
        return new ProviderMutationResult(
                ProviderOperationOutcome.SUCCEEDED,
                Optional.of(Objects.requireNonNull(order, "order")),
                "");
    }

    public static ProviderMutationResult failed(String publicReason) {
        return new ProviderMutationResult(
                ProviderOperationOutcome.FAILED, Optional.empty(), publicReason);
    }

    public static ProviderMutationResult needsReconciliation() {
        return new ProviderMutationResult(
                ProviderOperationOutcome.NEEDS_RECONCILIATION,
                Optional.empty(),
                "provider-result-unknown");
    }

    public static ProviderMutationResult needsUserAction(String publicReason) {
        return new ProviderMutationResult(
                ProviderOperationOutcome.NEEDS_USER_ACTION,
                Optional.empty(),
                publicReason);
    }
}
