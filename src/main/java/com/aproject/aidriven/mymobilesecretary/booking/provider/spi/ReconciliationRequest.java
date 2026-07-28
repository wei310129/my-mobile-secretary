package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import java.util.Objects;

public record ReconciliationRequest(
        String operationId, String provider, ProviderEnvironment environment) {

    public ReconciliationRequest {
        operationId = requireText(operationId, "operationId");
        provider = requireText(provider, "provider");
        Objects.requireNonNull(environment, "environment");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
