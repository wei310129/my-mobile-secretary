package com.aproject.aidriven.mymobilesecretary.booking.domain;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OfferSnapshot(
        UUID offerId,
        String provider,
        ProviderEnvironment environment,
        String inventoryIdentity,
        Instant retrievedAt,
        Instant expiresAt,
        BigDecimal totalPrice,
        Currency currency,
        Set<UUID> travellerIds,
        String termsFingerprint,
        boolean nonRefundable,
        boolean available,
        Set<ProviderCapability> capabilities) {

    public OfferSnapshot {
        Objects.requireNonNull(offerId, "offerId");
        provider = requireText(provider, "provider");
        Objects.requireNonNull(environment, "environment");
        inventoryIdentity = requireText(inventoryIdentity, "inventoryIdentity");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(totalPrice, "totalPrice");
        Objects.requireNonNull(currency, "currency");
        travellerIds = Set.copyOf(Objects.requireNonNull(travellerIds, "travellerIds"));
        termsFingerprint = requireText(termsFingerprint, "termsFingerprint");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        if (!retrievedAt.isBefore(expiresAt)) {
            throw new IllegalArgumentException("retrievedAt must be before expiresAt");
        }
        if (totalPrice.signum() < 0) {
            throw new IllegalArgumentException("totalPrice must not be negative");
        }
        if (travellerIds.isEmpty()) {
            throw new IllegalArgumentException("travellerIds must not be empty");
        }
    }

    public boolean isFresh(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return available && clock.instant().isBefore(expiresAt);
    }

    public Set<QuoteChange> materialChangesComparedWith(OfferSnapshot refreshed) {
        Objects.requireNonNull(refreshed, "refreshed");
        var changes = EnumSet.noneOf(QuoteChange.class);
        addIf(changes, QuoteChange.PROVIDER, !provider.equals(refreshed.provider));
        addIf(changes, QuoteChange.ENVIRONMENT, environment != refreshed.environment);
        addIf(
                changes,
                QuoteChange.INVENTORY,
                !inventoryIdentity.equals(refreshed.inventoryIdentity));
        addIf(changes, QuoteChange.PRICE, totalPrice.compareTo(refreshed.totalPrice) != 0);
        addIf(changes, QuoteChange.CURRENCY, !currency.equals(refreshed.currency));
        addIf(changes, QuoteChange.TRAVELLERS, !travellerIds.equals(refreshed.travellerIds));
        addIf(changes, QuoteChange.TERMS, !termsFingerprint.equals(refreshed.termsFingerprint));
        addIf(
                changes,
                QuoteChange.NON_REFUNDABLE,
                nonRefundable != refreshed.nonRefundable);
        addIf(changes, QuoteChange.AVAILABILITY, available != refreshed.available);
        addIf(changes, QuoteChange.CAPABILITIES, !capabilities.equals(refreshed.capabilities));
        return Set.copyOf(changes);
    }

    private static void addIf(
            EnumSet<QuoteChange> changes, QuoteChange change, boolean condition) {
        if (condition) {
            changes.add(change);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
