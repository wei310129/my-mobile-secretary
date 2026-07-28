package com.aproject.aidriven.mymobilesecretary.booking.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseAuthorizationTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID TRAVELLER = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void authorizesOnlyTheBoundActorWorkspaceOfferAndCapability() {
        var quote = OfferSnapshotTest.offer("100.00", "USD", "terms-v1", true, NOW.plusSeconds(60));
        var authorization = authorization(quote, NOW.plusSeconds(120), new BigDecimal("100.00"));

        authorization.assertAllows(
                quote, ProviderCapability.BOOK, WORKSPACE, ACTOR, CLOCK);

        assertViolation(
                () -> authorization.assertAllows(
                        quote,
                        ProviderCapability.BOOK,
                        UUID.fromString("00000000-0000-0000-0000-000000000399"),
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.WORKSPACE_MISMATCH);
        assertViolation(
                () -> authorization.assertAllows(
                        quote,
                        ProviderCapability.BOOK,
                        WORKSPACE,
                        UUID.fromString("00000000-0000-0000-0000-000000000398"),
                        CLOCK),
                AuthorizationFailure.ACTOR_MISMATCH);
        assertViolation(
                () -> authorization.assertAllows(
                        quote, ProviderCapability.HOLD, WORKSPACE, ACTOR, CLOCK),
                AuthorizationFailure.CAPABILITY_UNSUPPORTED);
    }

    @Test
    void rejectsStaleChangedOrUnavailableQuote() {
        var original = OfferSnapshotTest.offer("100.00", "USD", "terms-v1", true, NOW.plusSeconds(60));
        var authorization = authorization(original, NOW.plusSeconds(120), new BigDecimal("100.00"));

        assertViolation(
                () -> authorization.assertAllows(
                        OfferSnapshotTest.offer("100.00", "USD", "terms-v1", true, NOW),
                        ProviderCapability.BOOK,
                        WORKSPACE,
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.STALE_QUOTE);
        assertViolation(
                () -> authorization.assertAllows(
                        OfferSnapshotTest.offer("101.00", "USD", "terms-v1", true, NOW.plusSeconds(60)),
                        ProviderCapability.BOOK,
                        WORKSPACE,
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.PRICE_EXCEEDED);
        assertViolation(
                () -> authorization.assertAllows(
                        OfferSnapshotTest.offer("100.00", "USD", "terms-v2", true, NOW.plusSeconds(60)),
                        ProviderCapability.BOOK,
                        WORKSPACE,
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.TERMS_CHANGED);
        assertViolation(
                () -> authorization.assertAllows(
                        OfferSnapshotTest.offer("100.00", "USD", "terms-v1", false, NOW.plusSeconds(60)),
                        ProviderCapability.BOOK,
                        WORKSPACE,
                        ACTOR,
                        CLOCK),
                AuthorizationFailure.INVENTORY_UNAVAILABLE);
    }

    @Test
    void authorizationExpiryAndNonRefundableAcceptanceAreIndependentGates() {
        var refundable = OfferSnapshotTest.offer("100.00", "USD", "terms-v1", true, NOW.plusSeconds(60));
        var expired = authorization(refundable, NOW, new BigDecimal("100.00"));

        assertViolation(
                () -> expired.assertAllows(
                        refundable, ProviderCapability.BOOK, WORKSPACE, ACTOR, CLOCK),
                AuthorizationFailure.AUTHORIZATION_EXPIRED);

        var nonRefundable = new OfferSnapshot(
                refundable.offerId(),
                refundable.provider(),
                refundable.environment(),
                refundable.inventoryIdentity(),
                refundable.retrievedAt(),
                refundable.expiresAt(),
                refundable.totalPrice(),
                refundable.currency(),
                refundable.travellerIds(),
                refundable.termsFingerprint(),
                true,
                true,
                refundable.capabilities());
        var notAccepted = authorization(refundable, NOW.plusSeconds(120), new BigDecimal("100.00"));

        assertViolation(
                () -> notAccepted.assertAllows(
                        nonRefundable, ProviderCapability.BOOK, WORKSPACE, ACTOR, CLOCK),
                AuthorizationFailure.NON_REFUNDABLE_NOT_ACCEPTED);
    }

    static PurchaseAuthorization authorization(
            OfferSnapshot quote, Instant expiresAt, BigDecimal ceiling) {
        return new PurchaseAuthorization(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                WORKSPACE,
                ACTOR,
                quote.travellerIds(),
                quote.offerId(),
                quote.provider(),
                quote.environment(),
                ceiling,
                Currency.getInstance("USD"),
                quote.termsFingerprint(),
                false,
                expiresAt);
    }

    private static void assertViolation(Runnable invocation, AuthorizationFailure failure) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(AuthorizationViolation.class)
                .extracting("failure")
                .isEqualTo(failure);
    }
}
