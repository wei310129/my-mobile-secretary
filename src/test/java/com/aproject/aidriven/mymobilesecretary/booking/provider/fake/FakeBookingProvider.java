package com.aproject.aidriven.mymobilesecretary.booking.provider.fake;

import com.aproject.aidriven.mymobilesecretary.booking.domain.AuthorizationViolation;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrder;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ExternalBookingOrderStatus;
import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderCapability;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.AvailabilitySearchRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.BookingCancellationRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.BookingChangeRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.BookingProvider;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.BookingStatusRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ChangeProposal;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderMutationCommand;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ProviderMutationResult;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.QuoteRefreshRequest;
import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.ReconciliationRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class FakeBookingProvider implements BookingProvider {

    private final String provider;
    private final Clock clock;
    private final Map<UUID, OfferSnapshot> offers = new LinkedHashMap<>();
    private final Map<String, FakeMutationBehavior> scripts = new LinkedHashMap<>();
    private final Map<String, ProviderMutationResult> replayResults = new LinkedHashMap<>();
    private final Map<String, ExternalBookingOrder> unknownEffects = new LinkedHashMap<>();
    private final Map<UUID, ExternalBookingOrder> orders = new LinkedHashMap<>();
    private int externalMutationCount;

    FakeBookingProvider(String provider, Clock clock) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        this.provider = provider;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void registerOffer(OfferSnapshot offer) {
        validateOfferOwner(offer);
        if (offers.putIfAbsent(offer.offerId(), offer) != null) {
            throw new IllegalArgumentException("offer already exists");
        }
    }

    void replaceOffer(OfferSnapshot offer) {
        validateOfferOwner(offer);
        if (offers.replace(offer.offerId(), offer) == null) {
            throw new IllegalArgumentException("offer does not exist");
        }
    }

    void script(String operationId, FakeMutationBehavior behavior) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        scripts.put(operationId, Objects.requireNonNull(behavior, "behavior"));
    }

    int externalMutationCount() {
        return externalMutationCount;
    }

    @Override
    public List<OfferSnapshot> searchAvailability(AvailabilitySearchRequest request) {
        return offers.values().stream()
                .filter(OfferSnapshot::available)
                .filter(offer -> offer.travellerIds().equals(request.travellerIds()))
                .toList();
    }

    @Override
    public OfferSnapshot refreshQuote(QuoteRefreshRequest request) {
        var offer = offers.get(request.offerId());
        if (offer == null) {
            throw new IllegalArgumentException("offer not found");
        }
        return offer;
    }

    @Override
    public ProviderMutationResult hold(ProviderMutationCommand command) {
        return mutate(command, ProviderCapability.HOLD, ExternalBookingOrderStatus.HELD);
    }

    @Override
    public ProviderMutationResult book(ProviderMutationCommand command) {
        return mutate(command, ProviderCapability.BOOK, ExternalBookingOrderStatus.CONFIRMED);
    }

    @Override
    public ExternalBookingOrder getStatus(BookingStatusRequest request) {
        var order = orders.get(request.orderId());
        if (order == null) {
            throw new IllegalArgumentException("order not found");
        }
        if (!provider.equals(request.provider())
                || request.environment() != ProviderEnvironment.FAKE) {
            throw new IllegalArgumentException("provider boundary mismatch");
        }
        return order;
    }

    @Override
    public ChangeProposal proposeChange(BookingChangeRequest request) {
        var supported = request.order().provider().equals(provider);
        return new ChangeProposal(
                supported,
                supported ? "change-requires-new-authorization" : "provider-capability-unavailable");
    }

    @Override
    public ProviderMutationResult cancel(BookingCancellationRequest request) {
        return mutate(
                request.command(),
                ProviderCapability.CANCEL,
                ExternalBookingOrderStatus.CANCELLED);
    }

    @Override
    public ProviderMutationResult reconcileUnknownResult(ReconciliationRequest request) {
        if (!provider.equals(request.provider())
                || request.environment() != ProviderEnvironment.FAKE) {
            return ProviderMutationResult.failed("provider-boundary-mismatch");
        }
        var effect = unknownEffects.remove(request.operationId());
        if (effect == null) {
            return replayResults.getOrDefault(
                    request.operationId(),
                    ProviderMutationResult.failed("no-unknown-operation"));
        }
        orders.putIfAbsent(effect.orderId(), effect);
        var reconciled = ProviderMutationResult.succeeded(effect);
        replayResults.put(request.operationId(), reconciled);
        return reconciled;
    }

    private ProviderMutationResult mutate(
            ProviderMutationCommand command,
            ProviderCapability capability,
            ExternalBookingOrderStatus successStatus) {
        var replay = replayResults.get(command.operationId());
        if (replay != null) {
            return replay;
        }
        if (!command.offer().capabilities().contains(capability)) {
            return cacheFailure(command.operationId(), "provider-capability-unavailable");
        }
        try {
            command.authorization()
                    .assertAllows(
                            command.offer(),
                            capability,
                            command.workspaceId(),
                            command.actorId(),
                            clock);
        } catch (AuthorizationViolation violation) {
            return cacheFailure(command.operationId(), "authorization-rejected");
        }

        var behavior =
                scripts.getOrDefault(command.operationId(), FakeMutationBehavior.SUCCESS);
        if (behavior == FakeMutationBehavior.FAIL_BEFORE_SEND) {
            return cacheFailure(command.operationId(), "provider-rejected-before-send");
        }

        externalMutationCount++;
        var order = orderFor(command.operationId(), successStatus);
        if (behavior == FakeMutationBehavior.UNKNOWN_AFTER_SEND) {
            unknownEffects.put(command.operationId(), order);
            var unknown = ProviderMutationResult.needsReconciliation();
            replayResults.put(command.operationId(), unknown);
            return unknown;
        }

        orders.put(order.orderId(), order);
        var succeeded = ProviderMutationResult.succeeded(order);
        replayResults.put(command.operationId(), succeeded);
        return succeeded;
    }

    private ProviderMutationResult cacheFailure(String operationId, String publicReason) {
        var failed = ProviderMutationResult.failed(publicReason);
        replayResults.put(operationId, failed);
        return failed;
    }

    private ExternalBookingOrder orderFor(
            String operationId, ExternalBookingOrderStatus status) {
        var orderId =
                UUID.nameUUIDFromBytes(
                        (provider + ":" + operationId).getBytes(StandardCharsets.UTF_8));
        return new ExternalBookingOrder(
                orderId,
                provider,
                ProviderEnvironment.FAKE,
                "fake-" + orderId.toString().substring(0, 8),
                status,
                clock.instant());
    }

    private void validateOfferOwner(OfferSnapshot offer) {
        Objects.requireNonNull(offer, "offer");
        if (!provider.equals(offer.provider())
                || offer.environment() != ProviderEnvironment.FAKE) {
            throw new IllegalArgumentException("offer provider boundary mismatch");
        }
    }
}
