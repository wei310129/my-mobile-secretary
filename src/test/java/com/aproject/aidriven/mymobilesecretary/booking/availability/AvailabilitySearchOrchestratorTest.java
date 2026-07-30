package com.aproject.aidriven.mymobilesecretary.booking.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.booking.provider.spi.AvailabilitySearchRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AvailabilitySearchOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-07-29T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AvailabilitySearchRequest REQUEST =
            new AvailabilitySearchRequest(
                    Set.of(UUID.fromString("00000000-0000-0000-0000-000000000101")));

    @Test
    void excludesUnavailableExpiredAndImpossibleCandidates() {
        var candidates =
                List.of(
                        candidate(
                                1,
                                "alpha",
                                "100",
                                "USD",
                                true,
                                0,
                                AvailabilityFeasibility.FEASIBLE,
                                false,
                                NOW.plusSeconds(60),
                                AvailabilityRisk.LOW,
                                AvailabilityRisk.LOW,
                                0),
                        candidate(
                                2,
                                "alpha",
                                "100",
                                "USD",
                                true,
                                0,
                                AvailabilityFeasibility.FEASIBLE,
                                true,
                                NOW,
                                AvailabilityRisk.LOW,
                                AvailabilityRisk.LOW,
                                0),
                        candidate(
                                3,
                                "alpha",
                                "100",
                                "USD",
                                true,
                                0,
                                AvailabilityFeasibility.IMPOSSIBLE,
                                true,
                                NOW.plusSeconds(60),
                                AvailabilityRisk.LOW,
                                AvailabilityRisk.LOW,
                                0));

        var result = search(List.of(source("alpha", ignored -> candidates)));

        assertThat(result.status()).isEqualTo(AvailabilitySearchStatus.NO_RESULTS);
        assertThat(result.eligibleCandidates()).isEmpty();
        assertThat(result.recommendations()).isEmpty();
    }

    @Test
    void unknownFeeCandidateCannotWinCheapestTotal() {
        var unknown =
                candidate(
                        1,
                        "alpha",
                        "50",
                        "USD",
                        false,
                        0,
                        AvailabilityFeasibility.FEASIBLE,
                        true,
                        NOW.plusSeconds(60),
                        AvailabilityRisk.LOW,
                        AvailabilityRisk.LOW,
                        0);
        var known =
                candidate(
                        2,
                        "alpha",
                        "100",
                        "USD",
                        true,
                        1,
                        AvailabilityFeasibility.FEASIBLE,
                        true,
                        NOW.plusSeconds(60),
                        AvailabilityRisk.MEDIUM,
                        AvailabilityRisk.MEDIUM,
                        1);

        var result = search(List.of(source("alpha", ignored -> List.of(unknown, known))));

        assertThat(recommendation(result, RecommendationBadge.CHEAPEST_TOTAL).candidate())
                .isEqualTo(known);
    }

    @Test
    void incomparableCurrenciesDoNotProduceFalseCheapestBadge() {
        var usd = standardCandidate(1, "alpha", "100", "USD");
        var twd = standardCandidate(2, "alpha", "100", "TWD");

        var result = search(List.of(source("alpha", ignored -> List.of(usd, twd))));

        assertThat(result.recommendations())
                .flatExtracting(RecommendedCandidate::badges)
                .doesNotContain(RecommendationBadge.CHEAPEST_TOTAL);
    }

    @Test
    void flexibilityAndBalanceUseRiskFeasibilityAddOnsAndPriceInOrder() {
        var flexibleUnknown =
                candidate(
                        1,
                        "alpha",
                        "80",
                        "USD",
                        false,
                        0,
                        AvailabilityFeasibility.UNKNOWN,
                        true,
                        NOW.plusSeconds(60),
                        AvailabilityRisk.LOW,
                        AvailabilityRisk.LOW,
                        0);
        var balanced =
                candidate(
                        2,
                        "alpha",
                        "120",
                        "USD",
                        true,
                        0,
                        AvailabilityFeasibility.FEASIBLE,
                        true,
                        NOW.plusSeconds(60),
                        AvailabilityRisk.MEDIUM,
                        AvailabilityRisk.MEDIUM,
                        1);

        var result =
                search(
                        List.of(
                                source(
                                        "alpha",
                                        ignored -> List.of(flexibleUnknown, balanced))));

        assertThat(recommendation(result, RecommendationBadge.MOST_FLEXIBLE).candidate())
                .isEqualTo(flexibleUnknown);
        assertThat(recommendation(result, RecommendationBadge.BEST_BALANCE).candidate())
                .isEqualTo(balanced);
    }

    @Test
    void oneWinnerCarriesMultipleBadgesWithoutDuplicateRecommendation() {
        var winner = standardCandidate(1, "alpha", "100", "USD");

        var result = search(List.of(source("alpha", ignored -> List.of(winner))));

        assertThat(result.recommendations()).hasSize(1);
        assertThat(result.recommendations().getFirst().badges())
                .containsExactlyInAnyOrder(
                        RecommendationBadge.BEST_BALANCE,
                        RecommendationBadge.CHEAPEST_TOTAL,
                        RecommendationBadge.MOST_FLEXIBLE);
    }

    @Test
    void deterministicTieBreakAndFixedClockReplayReturnSameResult() {
        var laterByIdentity = standardCandidate(2, "alpha", "100", "USD");
        var earlierByIdentity = standardCandidate(1, "alpha", "100", "USD");
        var source =
                source(
                        "alpha",
                        ignored -> List.of(laterByIdentity, earlierByIdentity));

        var first = search(List.of(source));
        var replay = search(List.of(source));

        assertThat(first).isEqualTo(replay);
        assertThat(first.recommendations()).hasSize(1);
        assertThat(first.recommendations().getFirst().candidate())
                .isEqualTo(earlierByIdentity);
    }

    @Test
    void failedSourceDoesNotEraseTrustedCandidates() {
        var trusted = standardCandidate(1, "alpha", "100", "USD");

        var result =
                search(
                        List.of(
                                source("alpha", ignored -> List.of(trusted)),
                                source(
                                        "beta",
                                        ignored -> {
                                            throw new IllegalStateException("provider failure");
                                        })));

        assertThat(result.status()).isEqualTo(AvailabilitySearchStatus.PARTIAL);
        assertThat(result.eligibleCandidates()).containsExactly(trusted);
        assertThat(result.sourceOutcomes())
                .extracting(AvailabilitySourceOutcome::status)
                .containsExactly(
                        AvailabilitySourceStatus.SUCCESS,
                        AvailabilitySourceStatus.FAILED);
    }

    @Test
    void timedOutSourceDoesNotEraseCompletedSource() {
        var trusted = standardCandidate(1, "alpha", "100", "USD");
        var slow =
                source(
                        "beta",
                        ignored -> {
                            try {
                                Thread.sleep(5_000);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                            }
                            return List.of();
                        });

        var result =
                search(
                        List.of(source("alpha", ignored -> List.of(trusted)), slow),
                        Duration.ofMillis(50));

        assertThat(result.status()).isEqualTo(AvailabilitySearchStatus.PARTIAL);
        assertThat(result.eligibleCandidates()).containsExactly(trusted);
        assertThat(result.sourceOutcomes().get(1).status())
                .isEqualTo(AvailabilitySourceStatus.TIMED_OUT);
    }

    @Test
    void invalidSourceDataDoesNotEraseOtherSource() {
        var trusted = standardCandidate(1, "alpha", "100", "USD");

        var result =
                search(
                        List.of(
                                source("alpha", ignored -> List.of(trusted)),
                                source("beta", ignored -> null)));

        assertThat(result.status()).isEqualTo(AvailabilitySearchStatus.PARTIAL);
        assertThat(result.eligibleCandidates()).containsExactly(trusted);
        assertThat(result.sourceOutcomes().get(1).status())
                .isEqualTo(AvailabilitySourceStatus.INVALID_DATA);
    }

    @Test
    void progressIsStableAndExactlyOneTerminalIsEmitted() {
        var progress = new ArrayList<AvailabilitySearchProgress>();
        var terminalCount = new AtomicInteger();
        var alpha = standardCandidate(1, "alpha", "100", "USD");
        var beta = standardCandidate(2, "beta", "110", "USD");

        try (var orchestrator = new AvailabilitySearchOrchestrator(CLOCK)) {
            var result =
                    orchestrator.search(
                            REQUEST,
                            List.of(
                                    source("beta", ignored -> List.of(beta)),
                                    source("alpha", ignored -> List.of(alpha))),
                            Duration.ofSeconds(1),
                            progress::add,
                            ignored -> terminalCount.incrementAndGet());

            assertThat(result.status()).isEqualTo(AvailabilitySearchStatus.COMPLETE);
        }

        assertThat(progress)
                .extracting(AvailabilitySearchProgress::stage)
                .containsExactly(
                        AvailabilitySearchProgress.Stage.STARTED,
                        AvailabilitySearchProgress.Stage.SOURCE_COMPLETED,
                        AvailabilitySearchProgress.Stage.SOURCE_COMPLETED,
                        AvailabilitySearchProgress.Stage.TERMINAL);
        assertThat(progress)
                .extracting(AvailabilitySearchProgress::sequence)
                .containsExactly(0L, 1L, 2L, 3L);
        assertThat(progress.get(1).sourceKey()).contains("alpha");
        assertThat(progress.get(2).sourceKey()).contains("beta");
        assertThat(terminalCount).hasValue(1);
    }

    @Test
    void rejectsMoreThanTwoSourcesBeforeAnyTerminalEmission() {
        var terminalCount = new AtomicInteger();

        try (var orchestrator = new AvailabilitySearchOrchestrator(CLOCK)) {
            assertThatThrownBy(
                            () ->
                                    orchestrator.search(
                                            REQUEST,
                                            List.of(
                                                    source("alpha", ignored -> List.of()),
                                                    source("beta", ignored -> List.of()),
                                                    source("gamma", ignored -> List.of())),
                                            Duration.ofSeconds(1),
                                            ignored -> {},
                                            ignored -> terminalCount.incrementAndGet()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("one or two");
        }

        assertThat(terminalCount).hasValue(0);
    }

    @Test
    void allFailedSourcesProduceOneFailedTerminal() {
        var terminalResults = new ArrayList<AvailabilitySearchResult>();

        try (var orchestrator = new AvailabilitySearchOrchestrator(CLOCK)) {
            var result =
                    orchestrator.search(
                            REQUEST,
                            List.of(
                                    source(
                                            "alpha",
                                            ignored -> {
                                                throw new IllegalStateException("failed");
                                            })),
                            Duration.ofSeconds(1),
                            ignored -> {},
                            terminalResults::add);

            assertThat(result.status()).isEqualTo(AvailabilitySearchStatus.FAILED);
        }

        assertThat(terminalResults)
                .singleElement()
                .extracting(AvailabilitySearchResult::status)
                .isEqualTo(AvailabilitySearchStatus.FAILED);
    }

    private static AvailabilitySearchResult search(List<AvailabilitySource> sources) {
        return search(sources, Duration.ofSeconds(1));
    }

    private static AvailabilitySearchResult search(
            List<AvailabilitySource> sources, Duration timeout) {
        try (var orchestrator = new AvailabilitySearchOrchestrator(CLOCK)) {
            return orchestrator.search(
                    REQUEST, sources, timeout, ignored -> {}, ignored -> {});
        }
    }

    private static RecommendedCandidate recommendation(
            AvailabilitySearchResult result, RecommendationBadge badge) {
        return result.recommendations().stream()
                .filter(recommendation -> recommendation.badges().contains(badge))
                .findFirst()
                .orElseThrow();
    }

    private static AvailabilitySource source(
            String key,
            Function<AvailabilitySearchRequest, List<AvailabilityCandidate>> search) {
        return new AvailabilitySource() {
            @Override
            public String sourceKey() {
                return key;
            }

            @Override
            public List<AvailabilityCandidate> search(
                    AvailabilitySearchRequest request) {
                return search.apply(request);
            }
        };
    }

    private static AvailabilityCandidate standardCandidate(
            int id, String source, String price, String currency) {
        return candidate(
                id,
                source,
                price,
                currency,
                true,
                0,
                AvailabilityFeasibility.FEASIBLE,
                true,
                NOW.plusSeconds(60),
                AvailabilityRisk.LOW,
                AvailabilityRisk.LOW,
                0);
    }

    private static AvailabilityCandidate candidate(
            int id,
            String source,
            String price,
            String currency,
            boolean totalFullyKnown,
            int requiredAddOns,
            AvailabilityFeasibility feasibility,
            boolean available,
            Instant expiresAt,
            AvailabilityRisk refundRisk,
            AvailabilityRisk changeRisk,
            int restrictions) {
        return new AvailabilityCandidate(
                new UUID(0, id),
                source,
                "inventory-%02d".formatted(id),
                expiresAt,
                available,
                feasibility,
                new BigDecimal(price),
                Currency.getInstance(currency),
                totalFullyKnown,
                requiredAddOns,
                refundRisk,
                changeRisk,
                restrictions);
    }
}
