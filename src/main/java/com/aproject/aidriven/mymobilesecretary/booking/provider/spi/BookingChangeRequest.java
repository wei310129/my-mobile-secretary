package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import java.util.Objects;

public record BookingChangeRequest(
        ExternalBookingOrder order, String sanitizedChangeCode) {

    public BookingChangeRequest {
        Objects.requireNonNull(order, "order");
        if (sanitizedChangeCode == null || sanitizedChangeCode.isBlank()) {
            throw new IllegalArgumentException("sanitizedChangeCode must not be blank");
        }
    }
}
