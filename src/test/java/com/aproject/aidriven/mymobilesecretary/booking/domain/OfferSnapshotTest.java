package com.aproject.aidriven.mymobilesecretary.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfferSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID TRAVELLER = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void quoteIsFreshOnlyWhileAvailableAndStrictlyBeforeExpiry() {
        var fresh = offer("100.00", "USD", "terms-v1", true, NOW.plusSeconds(60));
        var expired = offer("100.00", "USD", "terms-v1", true, NOW);
        var unavailable = offer("100.00", "USD", "terms-v1", false, NOW.plusSeconds(60));

        assertThat(fresh.isFresh(CLOCK)).isTrue();
        assertThat(expired.isFresh(CLOCK)).isFalse();
        assertThat(unavailable.isFresh(CLOCK)).isFalse();
    }

    @Test
    void comparisonReportsEveryAuthorizationMaterialChange() {
        var original = offer("100.00", "USD", "terms-v1", true, NOW.plusSeconds(60));
        var changed = new OfferSnapshot(
                original.offerId(),
                "other-provider",
                ProviderEnvironment.SANDBOX,
                "inventory-2",
                NOW.plusSeconds(5),
                NOW.plusSeconds(120),
                new BigDecimal("110.00"),
                Currency.getInstance("TWD"),
                Set.of(UUID.fromString("00000000-0000-0000-0000-000000000102")),
                "terms-v2",
                true,
                false,
                Set.of(ProviderCapability.SEARCH, ProviderCapability.QUOTE));

        assertThat(original.materialChangesComparedWith(changed))
                .containsExactlyInAnyOrder(
                        QuoteChange.PROVIDER,
                        QuoteChange.ENVIRONMENT,
                        QuoteChange.INVENTORY,
                        QuoteChange.PRICE,
                        QuoteChange.CURRENCY,
                        QuoteChange.TRAVELLERS,
                        QuoteChange.TERMS,
                        QuoteChange.NON_REFUNDABLE,
                        QuoteChange.AVAILABILITY,
                        QuoteChange.CAPABILITIES);
    }

    static OfferSnapshot offer(
            String amount, String currency, String terms, boolean available, Instant expiresAt) {
        return new OfferSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                "fake-air",
                ProviderEnvironment.FAKE,
                "inventory-1",
                NOW.minusSeconds(5),
                expiresAt,
                new BigDecimal(amount),
                Currency.getInstance(currency),
                Set.of(TRAVELLER),
                terms,
                false,
                available,
                Set.of(
                        ProviderCapability.SEARCH,
                        ProviderCapability.QUOTE,
                        ProviderCapability.BOOK,
                        ProviderCapability.STATUS,
                        ProviderCapability.CANCEL));
    }
}
