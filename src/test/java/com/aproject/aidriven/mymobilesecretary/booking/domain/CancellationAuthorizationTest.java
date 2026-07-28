package com.aproject.aidriven.mymobilesecretary.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CancellationAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-07-28T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000703");

    @Test
    void exactGrantAllowsOnlyBoundActorWorkspaceProviderEnvironmentAndOrderBeforeExpiry() {
        ExternalBookingOrder order = order(ORDER, "fake-air", ProviderEnvironment.FAKE);
        CancellationAuthorization valid = authorization(
                WORKSPACE,
                ACTOR,
                ORDER,
                "fake-air",
                ProviderEnvironment.FAKE,
                NOW.plusSeconds(1));

        valid.assertAllows(order, WORKSPACE, ACTOR, CLOCK);

        assertViolation(
                () -> valid.assertAllows(order, UUID.randomUUID(), ACTOR, CLOCK),
                AuthorizationFailure.WORKSPACE_MISMATCH);
        assertViolation(
                () -> valid.assertAllows(order, WORKSPACE, UUID.randomUUID(), CLOCK),
                AuthorizationFailure.ACTOR_MISMATCH);
        assertViolation(
                () -> valid.assertAllows(
                        order(UUID.randomUUID(), "fake-air", ProviderEnvironment.FAKE),
                        WORKSPACE,
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.ORDER_MISMATCH);
        assertViolation(
                () -> valid.assertAllows(
                        order(ORDER, "other-provider", ProviderEnvironment.FAKE),
                        WORKSPACE,
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.PROVIDER_MISMATCH);
        assertViolation(
                () -> valid.assertAllows(
                        order(ORDER, "fake-air", ProviderEnvironment.LIVE),
                        WORKSPACE,
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.ENVIRONMENT_MISMATCH);
        CancellationAuthorization expired = authorization(
                WORKSPACE,
                ACTOR,
                ORDER,
                "fake-air",
                ProviderEnvironment.FAKE,
                NOW);
        assertViolation(
                () -> expired.assertAllows(order, WORKSPACE, ACTOR, CLOCK),
                AuthorizationFailure.AUTHORIZATION_EXPIRED);
    }

    private static CancellationAuthorization authorization(
            UUID workspaceId,
            UUID actorId,
            UUID orderId,
            String provider,
            ProviderEnvironment environment,
            Instant expiresAt) {
        return new CancellationAuthorization(
                UUID.randomUUID(),
                workspaceId,
                actorId,
                orderId,
                provider,
                environment,
                expiresAt);
    }

    private static ExternalBookingOrder order(
            UUID orderId, String provider, ProviderEnvironment environment) {
        return new ExternalBookingOrder(
                orderId,
                provider,
                environment,
                "masked-provider-reference",
                ExternalBookingOrderStatus.CONFIRMED,
                NOW);
    }

    private static void assertViolation(
            Runnable action, AuthorizationFailure expectedFailure) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        AuthorizationViolation.class,
                        violation -> assertThat(violation.failure()).isEqualTo(expectedFailure));
    }
}
