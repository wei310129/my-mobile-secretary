package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import java.util.Objects;
import java.util.UUID;

public record BookingStatusRequest(
        UUID orderId, String provider, ProviderEnvironment environment) {

    public BookingStatusRequest {
        Objects.requireNonNull(orderId, "orderId");
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        Objects.requireNonNull(environment, "environment");
    }
}
