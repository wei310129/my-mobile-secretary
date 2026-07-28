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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
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
    private final Map<String, ReplayEntry> replayResults = new LinkedHashMap<>();
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
        String digest = cancellationDigest(request);
        if (!provider.equals(request.order().provider())
                || request.order().environment() != ProviderEnvironment.FAKE) {
            return ProviderMutationResult.failed("provider-boundary-mismatch");
        }
        ProviderMutationResult replay = replayOrConflict(request.operationId(), digest);
        if (replay != null) {
            return replay;
        }
        try {
            request.authorization()
                    .assertAllows(
                            request.order(),
                            request.workspaceId(),
                            request.actorId(),
                            clock);
        } catch (AuthorizationViolation violation) {
            return cacheFailure(
                    request.operationId(), digest, "authorization-rejected");
        }
        FakeMutationBehavior behavior =
                scripts.getOrDefault(request.operationId(), FakeMutationBehavior.SUCCESS);
        if (behavior == FakeMutationBehavior.FAIL_BEFORE_SEND) {
            return cacheFailure(
                    request.operationId(), digest, "provider-rejected-before-send");
        }

        externalMutationCount++;
        ExternalBookingOrder order = new ExternalBookingOrder(
                request.order().orderId(),
                request.order().provider(),
                request.order().environment(),
                request.order().providerReference(),
                ExternalBookingOrderStatus.CANCELLED,
                clock.instant());
        if (behavior == FakeMutationBehavior.UNKNOWN_AFTER_SEND) {
            unknownEffects.put(request.operationId(), order);
            ProviderMutationResult unknown = ProviderMutationResult.needsReconciliation();
            replayResults.put(request.operationId(), new ReplayEntry(digest, unknown));
            return unknown;
        }
        orders.put(order.orderId(), order);
        ProviderMutationResult succeeded = ProviderMutationResult.succeeded(order);
        replayResults.put(request.operationId(), new ReplayEntry(digest, succeeded));
        return succeeded;
    }

    @Override
    public ProviderMutationResult reconcileUnknownResult(ReconciliationRequest request) {
        if (!provider.equals(request.provider())
                || request.environment() != ProviderEnvironment.FAKE) {
            return ProviderMutationResult.failed("provider-boundary-mismatch");
        }
        var effect = unknownEffects.remove(request.operationId());
        if (effect == null) {
            ReplayEntry replay = replayResults.get(request.operationId());
            return replay == null
                    ? ProviderMutationResult.failed("no-unknown-operation")
                    : replay.result();
        }
        orders.putIfAbsent(effect.orderId(), effect);
        var reconciled = ProviderMutationResult.succeeded(effect);
        replayResults.computeIfPresent(
                request.operationId(),
                (operationId, previous) -> new ReplayEntry(previous.digest(), reconciled));
        return reconciled;
    }

    private ProviderMutationResult mutate(
            ProviderMutationCommand command,
            ProviderCapability capability,
            ExternalBookingOrderStatus successStatus) {
        String digest = mutationDigest(command, capability);
        ProviderMutationResult replay = replayOrConflict(command.operationId(), digest);
        if (replay != null) {
            return replay;
        }
        if (!command.offer().capabilities().contains(capability)) {
            return cacheFailure(
                    command.operationId(), digest, "provider-capability-unavailable");
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
            return cacheFailure(command.operationId(), digest, "authorization-rejected");
        }

        var behavior =
                scripts.getOrDefault(command.operationId(), FakeMutationBehavior.SUCCESS);
        if (behavior == FakeMutationBehavior.FAIL_BEFORE_SEND) {
            return cacheFailure(
                    command.operationId(), digest, "provider-rejected-before-send");
        }

        externalMutationCount++;
        var order = orderFor(command.operationId(), successStatus);
        if (behavior == FakeMutationBehavior.UNKNOWN_AFTER_SEND) {
            unknownEffects.put(command.operationId(), order);
            var unknown = ProviderMutationResult.needsReconciliation();
            replayResults.put(command.operationId(), new ReplayEntry(digest, unknown));
            return unknown;
        }

        orders.put(order.orderId(), order);
        var succeeded = ProviderMutationResult.succeeded(order);
        replayResults.put(command.operationId(), new ReplayEntry(digest, succeeded));
        return succeeded;
    }

    private ProviderMutationResult replayOrConflict(String operationId, String digest) {
        ReplayEntry replay = replayResults.get(operationId);
        if (replay == null) {
            return null;
        }
        return replay.digest().equals(digest)
                ? replay.result()
                : ProviderMutationResult.failed("idempotency-conflict");
    }

    private ProviderMutationResult cacheFailure(
            String operationId, String digest, String publicReason) {
        var failed = ProviderMutationResult.failed(publicReason);
        replayResults.put(operationId, new ReplayEntry(digest, failed));
        return failed;
    }

    private static String mutationDigest(
            ProviderMutationCommand command, ProviderCapability capability) {
        var offer = command.offer();
        var authorization = command.authorization();
        return sha256(
                "mutation",
                capability.name(),
                command.workspaceId().toString(),
                command.actorId().toString(),
                offer.offerId().toString(),
                offer.provider(),
                offer.environment().name(),
                offer.inventoryIdentity(),
                offer.retrievedAt().toString(),
                offer.expiresAt().toString(),
                offer.totalPrice().stripTrailingZeros().toPlainString(),
                offer.currency().getCurrencyCode(),
                sorted(offer.travellerIds()),
                offer.termsFingerprint(),
                Boolean.toString(offer.nonRefundable()),
                Boolean.toString(offer.available()),
                sorted(offer.capabilities()),
                authorization.authorizationId().toString(),
                authorization.workspaceId().toString(),
                authorization.actorId().toString(),
                sorted(authorization.travellerIds()),
                authorization.offerId().toString(),
                authorization.provider(),
                authorization.environment().name(),
                authorization.maxTotalPrice().stripTrailingZeros().toPlainString(),
                authorization.currency().getCurrencyCode(),
                authorization.termsFingerprint(),
                Boolean.toString(authorization.nonRefundableAccepted()),
                authorization.expiresAt().toString(),
                authorization.confirmationMode().name(),
                authorization.substitutionStrength().name());
    }

    private static String cancellationDigest(BookingCancellationRequest request) {
        var order = request.order();
        var authorization = request.authorization();
        return sha256(
                "cancellation",
                request.workspaceId().toString(),
                request.actorId().toString(),
                order.orderId().toString(),
                order.provider(),
                order.environment().name(),
                order.providerReference(),
                order.status().name(),
                order.observedAt().toString(),
                authorization.authorizationId().toString(),
                authorization.workspaceId().toString(),
                authorization.actorId().toString(),
                authorization.orderId().toString(),
                authorization.provider(),
                authorization.environment().name(),
                authorization.expiresAt().toString());
    }

    private static String sorted(Iterable<?> values) {
        var sorted = new java.util.ArrayList<String>();
        values.forEach(value -> sorted.add(value.toString()));
        sorted.sort(String::compareTo);
        return String.join(",", sorted);
    }

    private static String sha256(String... components) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String component : components) {
                byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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

    private record ReplayEntry(String digest, ProviderMutationResult result) {}
}
