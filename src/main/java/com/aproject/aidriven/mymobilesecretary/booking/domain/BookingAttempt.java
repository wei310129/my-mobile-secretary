package com.aproject.aidriven.mymobilesecretary.booking.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record BookingAttempt(
        String operationId,
        BookingAttemptStatus status,
        Optional<UUID> orderId,
        String publicReason) {

    public BookingAttempt {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        orderId = Objects.requireNonNull(orderId, "orderId");
        publicReason = publicReason == null ? "" : publicReason;
    }

    public static BookingAttempt succeeded(String operationId, UUID orderId) {
        return new BookingAttempt(
                operationId,
                BookingAttemptStatus.SUCCEEDED,
                Optional.of(Objects.requireNonNull(orderId, "orderId")),
                "");
    }

    public static BookingAttempt failed(String operationId, String publicReason) {
        return new BookingAttempt(
                operationId, BookingAttemptStatus.FAILED, Optional.empty(), publicReason);
    }

    public static BookingAttempt unknown(String operationId) {
        return new BookingAttempt(
                operationId,
                BookingAttemptStatus.NEEDS_RECONCILIATION,
                Optional.empty(),
                "provider-result-unknown");
    }
}
