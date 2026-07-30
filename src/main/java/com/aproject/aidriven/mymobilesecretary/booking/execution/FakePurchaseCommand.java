package com.aproject.aidriven.mymobilesecretary.booking.execution;

import java.util.Objects;
import java.util.UUID;

public record FakePurchaseCommand(
        UUID planId,
        UUID authorizationId,
        String operationId,
        UUID workspaceId,
        UUID actorId) {

    public FakePurchaseCommand {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
    }
}
