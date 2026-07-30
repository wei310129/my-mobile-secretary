package com.aproject.aidriven.mymobilesecretary.booking.execution;

import com.aproject.aidriven.mymobilesecretary.booking.domain.BookingExecutionState;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderOperationOutcome;
import java.util.Objects;

public record FakePurchaseResult(
        BookingExecutionState state,
        ProviderOperationOutcome providerOutcome,
        boolean cancellationProposalAvailable,
        boolean replacementProposalAvailable,
        String publicReason) {

    public FakePurchaseResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(providerOutcome, "providerOutcome");
        publicReason = publicReason == null ? "" : publicReason;
    }
}
