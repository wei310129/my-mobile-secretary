package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import com.aproject.aidriven.mymobilesecretary.booking.domain.PurchaseAuthorization;
import java.util.Objects;
import java.util.UUID;

public record ProviderMutationCommand(
        String operationId,
        OfferSnapshot offer,
        PurchaseAuthorization authorization,
        UUID workspaceId,
        UUID actorId) {

    public ProviderMutationCommand {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
    }
}
