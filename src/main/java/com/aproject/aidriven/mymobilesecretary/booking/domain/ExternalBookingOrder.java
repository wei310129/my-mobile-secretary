package com.aproject.aidriven.mymobilesecretary.booking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExternalBookingOrder(
        UUID orderId,
        String provider,
        ProviderEnvironment environment,
        String providerReference,
        ExternalBookingOrderStatus status,
        Instant observedAt) {

    public ExternalBookingOrder {
        Objects.requireNonNull(orderId, "orderId");
        provider = requireText(provider, "provider");
        Objects.requireNonNull(environment, "environment");
        providerReference = requireText(providerReference, "providerReference");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
