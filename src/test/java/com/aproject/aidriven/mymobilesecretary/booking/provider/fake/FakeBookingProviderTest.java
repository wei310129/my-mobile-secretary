package com.aproject.aidriven.mymobilesecretary.booking.provider.fake;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderCapability;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import com.aproject.aidriven.mymobilesecretary.booking.domain.PurchaseAuthorization;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.AvailabilitySearchRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderMutationCommand;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderOperationOutcome;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.QuoteRefreshRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ReconciliationRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FakeBookingProviderTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000301");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000302");
    private static final UUID TRAVELLER = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void unknownAfterSendMutatesOnceAndMustReconcileBeforeRetry() {
        var provider = new FakeBookingProvider("fake-air", CLOCK);
        var offer = offer(true, "100.00", "terms-v1");
        var authorization = authorization(offer);
        provider.registerOffer(offer);
        provider.script("book-1", FakeMutationBehavior.UNKNOWN_AFTER_SEND);
        var command =
                new ProviderMutationCommand("book-1", offer, authorization, WORKSPACE, ACTOR);

        var first = provider.book(command);
        var replay = provider.book(command);
        var reconciled = provider.reconcileUnknownResult(
                new ReconciliationRequest("book-1", "fake-air", ProviderEnvironment.FAKE));

        assertThat(first.outcome()).isEqualTo(ProviderOperationOutcome.NEEDS_RECONCILIATION);
        assertThat(replay).isEqualTo(first);
        assertThat(reconciled.outcome()).isEqualTo(ProviderOperationOutcome.SUCCEEDED);
        assertThat(provider.externalMutationCount()).isEqualTo(1);
    }

    @Test
    void searchAndRefreshExposeInventoryDisappearanceAndMaterialQuoteChange() {
        var provider = new FakeBookingProvider("fake-air", CLOCK);
        var original = offer(true, "100.00", "terms-v1");
        provider.registerOffer(original);

        assertThat(provider.searchAvailability(new AvailabilitySearchRequest(Set.of(TRAVELLER))))
                .containsExactly(original);

        var changed = offer(false, "110.00", "terms-v2");
        provider.replaceOffer(changed);
        var refreshed = provider.refreshQuote(new QuoteRefreshRequest(original.offerId()));

        assertThat(refreshed.available()).isFalse();
        assertThat(original.materialChangesComparedWith(refreshed))
                .contains(
                        com.aproject.aidriven.mymobilesecretary.booking.domain.QuoteChange.PRICE,
                        com.aproject.aidriven.mymobilesecretary.booking.domain.QuoteChange.TERMS,
                        com.aproject.aidriven.mymobilesecretary.booking.domain.QuoteChange.AVAILABILITY);
        assertThat(provider.searchAvailability(new AvailabilitySearchRequest(Set.of(TRAVELLER))))
                .isEmpty();
    }

    @Test
    void unsupportedMutationFailsBeforeExternalMutation() {
        var provider = new FakeBookingProvider("fake-air", CLOCK);
        var offer = offer(true, "100.00", "terms-v1");
        var command =
                new ProviderMutationCommand("hold-1", offer, authorization(offer), WORKSPACE, ACTOR);
        provider.registerOffer(offer);

        var result = provider.hold(command);

        assertThat(result.outcome()).isEqualTo(ProviderOperationOutcome.FAILED);
        assertThat(result.publicReason()).isEqualTo("provider-capability-unavailable");
        assertThat(provider.externalMutationCount()).isZero();
    }

    private static OfferSnapshot offer(
            boolean available, String amount, String termsFingerprint) {
        return new OfferSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                "fake-air",
                ProviderEnvironment.FAKE,
                "inventory-1",
                NOW.minusSeconds(5),
                NOW.plusSeconds(60),
                new BigDecimal(amount),
                Currency.getInstance("USD"),
                Set.of(TRAVELLER),
                termsFingerprint,
                false,
                available,
                Set.of(
                        ProviderCapability.SEARCH,
                        ProviderCapability.QUOTE,
                        ProviderCapability.BOOK,
                        ProviderCapability.STATUS,
                        ProviderCapability.CANCEL));
    }

    private static PurchaseAuthorization authorization(OfferSnapshot offer) {
        return new PurchaseAuthorization(
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                WORKSPACE,
                ACTOR,
                Set.of(TRAVELLER),
                offer.offerId(),
                "fake-air",
                ProviderEnvironment.FAKE,
                new BigDecimal("100.00"),
                Currency.getInstance("USD"),
                "terms-v1",
                false,
                NOW.plusSeconds(120));
    }
}
