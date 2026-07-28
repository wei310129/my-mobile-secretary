package com.aproject.aidriven.mymobilesecretary.booking.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PurchaseAuthorization {

    private final UUID authorizationId;
    private final UUID workspaceId;
    private final UUID actorId;
    private final Set<UUID> travellerIds;
    private final UUID offerId;
    private final String provider;
    private final ProviderEnvironment environment;
    private final BigDecimal maxTotalPrice;
    private final Currency currency;
    private final String termsFingerprint;
    private final boolean nonRefundableAccepted;
    private final Instant expiresAt;
    private final ConfirmationMode confirmationMode;
    private final SubstitutionStrength substitutionStrength;

    public PurchaseAuthorization(
            UUID authorizationId,
            UUID workspaceId,
            UUID actorId,
            Set<UUID> travellerIds,
            UUID offerId,
            String provider,
            ProviderEnvironment environment,
            BigDecimal maxTotalPrice,
            Currency currency,
            String termsFingerprint,
            boolean nonRefundableAccepted,
            Instant expiresAt) {
        this(
                authorizationId,
                workspaceId,
                actorId,
                travellerIds,
                offerId,
                provider,
                environment,
                maxTotalPrice,
                currency,
                termsFingerprint,
                nonRefundableAccepted,
                expiresAt,
                ConfirmationMode.PER_ITEM,
                SubstitutionStrength.EXACT);
    }

    public PurchaseAuthorization(
            UUID authorizationId,
            UUID workspaceId,
            UUID actorId,
            Set<UUID> travellerIds,
            UUID offerId,
            String provider,
            ProviderEnvironment environment,
            BigDecimal maxTotalPrice,
            Currency currency,
            String termsFingerprint,
            boolean nonRefundableAccepted,
            Instant expiresAt,
            ConfirmationMode confirmationMode,
            SubstitutionStrength substitutionStrength) {
        this.authorizationId = Objects.requireNonNull(authorizationId, "authorizationId");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.travellerIds =
                Set.copyOf(Objects.requireNonNull(travellerIds, "travellerIds"));
        this.offerId = Objects.requireNonNull(offerId, "offerId");
        this.provider = requireText(provider, "provider");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.maxTotalPrice = Objects.requireNonNull(maxTotalPrice, "maxTotalPrice");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.termsFingerprint = requireText(termsFingerprint, "termsFingerprint");
        this.nonRefundableAccepted = nonRefundableAccepted;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.confirmationMode =
                Objects.requireNonNull(confirmationMode, "confirmationMode");
        this.substitutionStrength =
                Objects.requireNonNull(substitutionStrength, "substitutionStrength");
        if (this.travellerIds.isEmpty()) {
            throw new IllegalArgumentException("travellerIds must not be empty");
        }
        if (this.maxTotalPrice.signum() < 0) {
            throw new IllegalArgumentException("maxTotalPrice must not be negative");
        }
    }

    public void assertAllows(
            OfferSnapshot quote,
            ProviderCapability capability,
            UUID actualWorkspaceId,
            UUID actualActorId,
            Clock clock) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(clock, "clock");
        rejectUnless(workspaceId.equals(actualWorkspaceId), AuthorizationFailure.WORKSPACE_MISMATCH);
        rejectUnless(actorId.equals(actualActorId), AuthorizationFailure.ACTOR_MISMATCH);
        rejectUnless(clock.instant().isBefore(expiresAt), AuthorizationFailure.AUTHORIZATION_EXPIRED);
        rejectUnless(offerId.equals(quote.offerId()), AuthorizationFailure.OFFER_MISMATCH);
        rejectUnless(provider.equals(quote.provider()), AuthorizationFailure.PROVIDER_MISMATCH);
        rejectUnless(environment == quote.environment(), AuthorizationFailure.ENVIRONMENT_MISMATCH);
        rejectUnless(travellerIds.equals(quote.travellerIds()), AuthorizationFailure.TRAVELLER_MISMATCH);
        rejectUnless(quote.available(), AuthorizationFailure.INVENTORY_UNAVAILABLE);
        rejectUnless(clock.instant().isBefore(quote.expiresAt()), AuthorizationFailure.STALE_QUOTE);
        rejectUnless(
                quote.capabilities().contains(capability),
                AuthorizationFailure.CAPABILITY_UNSUPPORTED);
        rejectUnless(
                maxTotalPrice.compareTo(quote.totalPrice()) >= 0,
                AuthorizationFailure.PRICE_EXCEEDED);
        rejectUnless(currency.equals(quote.currency()), AuthorizationFailure.CURRENCY_MISMATCH);
        rejectUnless(
                termsFingerprint.equals(quote.termsFingerprint()),
                AuthorizationFailure.TERMS_CHANGED);
        rejectUnless(
                !quote.nonRefundable() || nonRefundableAccepted,
                AuthorizationFailure.NON_REFUNDABLE_NOT_ACCEPTED);
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

    public Set<UUID> travellerIds() {
        return travellerIds;
    }

    public UUID offerId() {
        return offerId;
    }

    public String provider() {
        return provider;
    }

    public ProviderEnvironment environment() {
        return environment;
    }

    public BigDecimal maxTotalPrice() {
        return maxTotalPrice;
    }

    public Currency currency() {
        return currency;
    }

    public String termsFingerprint() {
        return termsFingerprint;
    }

    public boolean nonRefundableAccepted() {
        return nonRefundableAccepted;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public ConfirmationMode confirmationMode() {
        return confirmationMode;
    }

    public SubstitutionStrength substitutionStrength() {
        return substitutionStrength;
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
