package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.CancellationAuthorization;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import java.util.Objects;
import java.util.UUID;

public record BookingCancellationRequest(
        ExternalBookingOrder order,
        CancellationAuthorization authorization,
        String operationId,
        UUID workspaceId,
        UUID actorId) {

    public BookingCancellationRequest {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(authorization, "authorization");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
    }
}
