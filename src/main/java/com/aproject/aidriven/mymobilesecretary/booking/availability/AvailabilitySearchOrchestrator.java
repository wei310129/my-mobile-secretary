package com.aproject.aidriven.mymobilesecretary.booking.availability;

import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.AvailabilitySearchRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class AvailabilitySearchOrchestrator implements AutoCloseable {

    private static final int MAX_SOURCES = 2;
    private static final Comparator<AvailabilityCandidate> STABLE_TIE_BREAK =
            Comparator.comparing(AvailabilityCandidate::sourceKey)
                    .thenComparing(AvailabilityCandidate::inventoryIdentity)
                    .thenComparing(candidate -> candidate.candidateId().toString());
    private static final Comparator<AvailabilityCandidate> PRICE_ORDER =
            Comparator.comparing(
                            (AvailabilityCandidate candidate) ->
                                    candidate.currency().getCurrencyCode())
                    .thenComparing(AvailabilityCandidate::displayedPrice)
                    .thenComparing(STABLE_TIE_BREAK);
    private static final Comparator<AvailabilityCandidate> FLEXIBILITY_ORDER =
            Comparator.comparingInt(
                            (AvailabilityCandidate candidate) ->
                                    riskRank(candidate.refundRisk())
                                            + riskRank(candidate.changeRisk()))
                    .thenComparingInt(candidate -> riskRank(candidate.refundRisk()))
                    .thenComparingInt(candidate -> riskRank(candidate.changeRisk()))
                    .thenComparingInt(AvailabilityCandidate::restrictionCount)
                    .thenComparingInt(AvailabilityCandidate::requiredAddOnCount)
                    .thenComparing(candidate -> !candidate.totalFullyKnown())
                    .thenComparing(STABLE_TIE_BREAK);
    private static final Comparator<AvailabilityCandidate> BALANCE_ORDER =
            Comparator.comparingInt(
                            (AvailabilityCandidate candidate) ->
                                    candidate.feasibility()
                                                    == AvailabilityFeasibility.FEASIBLE
                                            ? 0
                                            : 1)
                    .thenComparing(candidate -> !candidate.totalFullyKnown())
                    .thenComparingInt(AvailabilityCandidate::requiredAddOnCount)
                    .thenComparingInt(
                            candidate ->
                                    riskRank(candidate.refundRisk())
                                            + riskRank(candidate.changeRisk()))
                    .thenComparingInt(AvailabilityCandidate::restrictionCount)
                    .thenComparing(PRICE_ORDER);

    private final Clock clock;
    private final ExecutorService executor;

    public AvailabilitySearchOrchestrator(Clock clock) {
        this(clock, Executors.newFixedThreadPool(MAX_SOURCES));
    }

    AvailabilitySearchOrchestrator(Clock clock, ExecutorService executor) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public AvailabilitySearchResult search(
            AvailabilitySearchRequest request,
            List<AvailabilitySource> sources,
            Duration timeout,
            AvailabilityProgressSink progressSink,
            AvailabilityTerminalSink terminalSink) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(progressSink, "progressSink");
        Objects.requireNonNull(terminalSink, "terminalSink");
        if (sources.isEmpty() || sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("sources must contain one or two entries");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        var orderedSources =
                sources.stream().sorted(Comparator.comparing(AvailabilitySource::sourceKey)).toList();
        validateSourceKeys(orderedSources);
        progressSink.emit(AvailabilitySearchProgress.started());

        List<Future<SourceResult>> futures;
        try {
            var tasks =
                    orderedSources.stream()
                            .<Callable<SourceResult>>map(
                                    source -> () -> searchSource(source, request))
                            .toList();
            futures =
                    executor.invokeAll(
                            tasks, timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("availability search interrupted", exception);
        }

        var sourceResults = new ArrayList<SourceResult>(orderedSources.size());
        for (int index = 0; index < orderedSources.size(); index++) {
            sourceResults.add(resolve(orderedSources.get(index), futures.get(index)));
        }

        long sequence = 1;
        for (var sourceResult : sourceResults) {
            progressSink.emit(
                    AvailabilitySearchProgress.sourceCompleted(
                            sequence++, sourceResult.outcome()));
        }

        var eligible =
                sourceResults.stream()
                        .flatMap(result -> result.candidates().stream())
                        .filter(candidate -> candidate.isEligible(clock))
                        .sorted(STABLE_TIE_BREAK)
                        .toList();
        var outcomes = sourceResults.stream().map(SourceResult::outcome).toList();
        var result =
                new AvailabilitySearchResult(
                        searchStatus(outcomes, eligible),
                        eligible,
                        recommend(eligible),
                        outcomes);
        progressSink.emit(AvailabilitySearchProgress.terminal(sequence));
        terminalSink.emit(result);
        return result;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static SourceResult searchSource(
            AvailabilitySource source, AvailabilitySearchRequest request) {
        try {
            var candidates = source.search(request);
            validateCandidates(source.sourceKey(), candidates);
            return new SourceResult(
                    new AvailabilitySourceOutcome(
                            source.sourceKey(),
                            AvailabilitySourceStatus.SUCCESS,
                            candidates.size()),
                    List.copyOf(candidates));
        } catch (InvalidSourceDataException exception) {
            return failedSource(source.sourceKey(), AvailabilitySourceStatus.INVALID_DATA);
        } catch (RuntimeException exception) {
            return failedSource(source.sourceKey(), AvailabilitySourceStatus.FAILED);
        }
    }

    private static SourceResult resolve(AvailabilitySource source, Future<SourceResult> future) {
        if (future.isCancelled()) {
            return failedSource(source.sourceKey(), AvailabilitySourceStatus.TIMED_OUT);
        }
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("availability result interrupted", exception);
        } catch (ExecutionException exception) {
            return failedSource(source.sourceKey(), AvailabilitySourceStatus.FAILED);
        }
    }

    private static SourceResult failedSource(
            String sourceKey, AvailabilitySourceStatus status) {
        return new SourceResult(
                new AvailabilitySourceOutcome(sourceKey, status, 0), List.of());
    }

    private static void validateSourceKeys(List<AvailabilitySource> sources) {
        var keys = new HashSet<String>();
        for (var source : sources) {
            Objects.requireNonNull(source, "source");
            var key = source.sourceKey();
            if (key == null || key.isBlank() || !keys.add(key)) {
                throw new IllegalArgumentException(
                        "source keys must be non-blank and unique");
            }
        }
    }

    private static void validateCandidates(
            String sourceKey, List<AvailabilityCandidate> candidates) {
        if (candidates == null) {
            throw new InvalidSourceDataException();
        }
        var candidateIds = new HashSet<>();
        for (var candidate : candidates) {
            if (candidate == null
                    || !sourceKey.equals(candidate.sourceKey())
                    || !candidateIds.add(candidate.candidateId())) {
                throw new InvalidSourceDataException();
            }
        }
    }

    private static AvailabilitySearchStatus searchStatus(
            List<AvailabilitySourceOutcome> outcomes,
            List<AvailabilityCandidate> eligible) {
        var successCount =
                outcomes.stream()
                        .filter(outcome -> outcome.status() == AvailabilitySourceStatus.SUCCESS)
                        .count();
        if (successCount == 0) {
            return AvailabilitySearchStatus.FAILED;
        }
        if (successCount < outcomes.size()) {
            return AvailabilitySearchStatus.PARTIAL;
        }
        return eligible.isEmpty()
                ? AvailabilitySearchStatus.NO_RESULTS
                : AvailabilitySearchStatus.COMPLETE;
    }

    private static List<RecommendedCandidate> recommend(
            List<AvailabilityCandidate> eligible) {
        if (eligible.isEmpty()) {
            return List.of();
        }

        var badgesByCandidate = new LinkedHashMap<AvailabilityCandidate, EnumSet<RecommendationBadge>>();
        addBadge(
                badgesByCandidate,
                eligible.stream().min(BALANCE_ORDER).orElseThrow(),
                RecommendationBadge.BEST_BALANCE);
        cheapestComparableCandidate(eligible)
                .ifPresent(
                        candidate ->
                                addBadge(
                                        badgesByCandidate,
                                        candidate,
                                        RecommendationBadge.CHEAPEST_TOTAL));
        addBadge(
                badgesByCandidate,
                eligible.stream().min(FLEXIBILITY_ORDER).orElseThrow(),
                RecommendationBadge.MOST_FLEXIBLE);

        return badgesByCandidate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(STABLE_TIE_BREAK))
                .map(
                        entry ->
                                new RecommendedCandidate(
                                        entry.getKey(), entry.getValue()))
                .toList();
    }

    private static java.util.Optional<AvailabilityCandidate> cheapestComparableCandidate(
            List<AvailabilityCandidate> eligible) {
        var knownTotals =
                eligible.stream().filter(AvailabilityCandidate::totalFullyKnown).toList();
        Set<Currency> currencies =
                knownTotals.stream()
                        .map(AvailabilityCandidate::currency)
                        .collect(java.util.stream.Collectors.toSet());
        if (currencies.size() != 1) {
            return java.util.Optional.empty();
        }
        return knownTotals.stream().min(PRICE_ORDER);
    }

    private static void addBadge(
            Map<AvailabilityCandidate, EnumSet<RecommendationBadge>> badges,
            AvailabilityCandidate candidate,
            RecommendationBadge badge) {
        badges.computeIfAbsent(
                        candidate, ignored -> EnumSet.noneOf(RecommendationBadge.class))
                .add(badge);
    }

    private static int riskRank(AvailabilityRisk risk) {
        return switch (risk) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case UNKNOWN -> 3;
        };
    }

    private record SourceResult(
            AvailabilitySourceOutcome outcome, List<AvailabilityCandidate> candidates) {}

    private static final class InvalidSourceDataException extends RuntimeException {}
}
