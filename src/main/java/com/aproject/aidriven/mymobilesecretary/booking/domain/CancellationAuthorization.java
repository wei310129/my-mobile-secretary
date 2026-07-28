package com.aproject.aidriven.mymobilesecretary.booking.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class CancellationAuthorization {

    private final UUID authorizationId;
    private final UUID workspaceId;
    private final UUID actorId;
    private final UUID orderId;
    private final String provider;
    private final ProviderEnvironment environment;
    private final Instant expiresAt;

    public CancellationAuthorization(
            UUID authorizationId,
            UUID workspaceId,
            UUID actorId,
            UUID orderId,
            String provider,
            ProviderEnvironment environment,
            Instant expiresAt) {
        this.authorizationId = Objects.requireNonNull(authorizationId, "authorizationId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.provider = requireText(provider, "provider");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public void assertAllows(
            ExternalBookingOrder order,
            UUID actualWorkspaceId,
            UUID actualActorId,
            Clock clock) {
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(actualWorkspaceId, "actualWorkspaceId");
        Objects.requireNonNull(actualActorId, "actualActorId");
        Objects.requireNonNull(clock, "clock");
        rejectUnless(workspaceId.equals(actualWorkspaceId), AuthorizationFailure.WORKSPACE_MISMATCH);
        rejectUnless(actorId.equals(actualActorId), AuthorizationFailure.ACTOR_MISMATCH);
        rejectUnless(clock.instant().isBefore(expiresAt), AuthorizationFailure.AUTHORIZATION_EXPIRED);
        rejectUnless(orderId.equals(order.orderId()), AuthorizationFailure.ORDER_MISMATCH);
        rejectUnless(provider.equals(order.provider()), AuthorizationFailure.PROVIDER_MISMATCH);
        rejectUnless(environment == order.environment(), AuthorizationFailure.ENVIRONMENT_MISMATCH);
    }

    public UUID authorizationId() {
        return authorizationId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID orderId() {
        return orderId;
    }

    public String provider() {
        return provider;
    }

    public ProviderEnvironment environment() {
        return environment;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    private static void rejectUnless(boolean accepted, AuthorizationFailure failure) {
        if (!accepted) {
            throw new AuthorizationViolation(failure);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
