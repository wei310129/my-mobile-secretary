package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import java.util.Objects;

public record BookingCancellationRequest(
        ExternalBookingOrder order, ProviderMutationCommand command) {

    public BookingCancellationRequest {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(command, "command");
    }
}
