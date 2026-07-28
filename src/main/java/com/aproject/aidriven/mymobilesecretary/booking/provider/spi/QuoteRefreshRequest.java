package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import java.util.Objects;
import java.util.UUID;

public record QuoteRefreshRequest(UUID offerId) {

    public QuoteRefreshRequest {
        Objects.requireNonNull(offerId, "offerId");
    }
}
