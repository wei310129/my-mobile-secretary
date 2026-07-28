package com.aproject.aidriven.mymobilesecretary.booking.provider.spi;

import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import java.util.List;

public interface BookingProvider {

    List<OfferSnapshot> searchAvailability(AvailabilitySearchRequest request);

    OfferSnapshot refreshQuote(QuoteRefreshRequest request);

    ProviderMutationResult hold(ProviderMutationCommand command);

    ProviderMutationResult book(ProviderMutationCommand command);

    ExternalBookingOrder getStatus(BookingStatusRequest request);

    ChangeProposal proposeChange(BookingChangeRequest request);

    ProviderMutationResult cancel(BookingCancellationRequest request);

    ProviderMutationResult reconcileUnknownResult(ReconciliationRequest request);
}
