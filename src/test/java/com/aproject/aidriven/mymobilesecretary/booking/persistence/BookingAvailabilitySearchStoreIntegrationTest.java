package com.aproject.aidriven.mymobilesecretary.booking.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityCandidate;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityFeasibility;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityRisk;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySearchProgress;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySearchResult;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySearchStatus;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySourceOutcome;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySourceStatus;
import com.aproject.aidriven.mymobilesecretary.booking.availability.RecommendationBadge;
import com.aproject.aidriven.mymobilesecretary.booking.availability.RecommendedCandidate;
import com.aproject.aidriven.mymobilesecretary.booking.domain.OfferSnapshot;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderCapability;
import com.aproject.aidriven.mymobilesecretary.booking.domain.ProviderEnvironment;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BookingAvailabilitySearchStoreIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE = "mms_booking_search_rls_runtime";

    @Autowired private BookingAvailabilitySearchStore store;
    @Autowired private BookingExecutionStore executionStore;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private Clock clock;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                        WHERE rolname = 'mms_booking_search_rls_runtime') THEN
                        CREATE ROLE mms_booking_search_rls_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void persistsTypedProgressCandidatesAndExactlyOneTerminalAtomically() {
        Fixture fixture = fixture("complete");

        BookingAvailabilitySearchStore.SearchJob first =
                inContext(fixture.context(), () -> store.startSearch("search-complete"));
        BookingAvailabilitySearchStore.SearchJob replay =
                inContext(fixture.context(), () -> store.startSearch("search-complete"));
        assertThat(replay).isEqualTo(first);

        inContext(fixture.context(), () -> {
            store.progressSink(first.searchJobId()).emit(AvailabilitySearchProgress.started());
            store.progressSink(first.searchJobId())
                    .emit(AvailabilitySearchProgress.sourceCompleted(
                            1, fixture.result().sourceOutcomes().getFirst()));
            store.progressSink(first.searchJobId()).emit(AvailabilitySearchProgress.terminal(2));
            store.terminalSink(first.searchJobId()).emit(fixture.result());
            store.terminalSink(first.searchJobId()).emit(fixture.result());
            return null;
        });

        inContext(fixture.context(), () -> {
            BookingAvailabilitySearchStore.SearchView view =
                    store.loadSearch(first.searchJobId()).orElseThrow();
            assertThat(view.status())
                    .isEqualTo(BookingAvailabilitySearchStore.SearchJobStatus.COMPLETE);
            assertThat(view.candidates())
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(List.of(fixture.candidate()));
            assertThat(view.recommendations())
                    .usingRecursiveComparison()
                    .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                    .isEqualTo(fixture.result().recommendations());
            assertThat(view.progress())
                    .extracting(AvailabilitySearchProgress::stage)
                    .containsExactly(
                            AvailabilitySearchProgress.Stage.STARTED,
                            AvailabilitySearchProgress.Stage.SOURCE_COMPLETED,
                            AvailabilitySearchProgress.Stage.TERMINAL);
            return null;
        });
        assertThat(countById("booking_search_job", first.searchJobId())).isEqualTo(1);
        assertThat(countByJob("booking_search_candidate", first.searchJobId())).isEqualTo(1);
        assertThat(countByJob("booking_search_progress", first.searchJobId())).isEqualTo(3);
        assertThat(countByJob("booking_search_terminal_outbox", first.searchJobId())).isEqualTo(1);

        AvailabilitySearchResult conflicting =
                new AvailabilitySearchResult(
                        AvailabilitySearchStatus.FAILED,
                        List.of(),
                        List.of(),
                        List.of(new AvailabilitySourceOutcome(
                                "source-a", AvailabilitySourceStatus.FAILED, 0)));
        assertThatThrownBy(() -> inContext(fixture.context(), () -> {
                    store.terminalSink(first.searchJobId()).emit(conflicting);
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(countByJob("booking_search_terminal_outbox", first.searchJobId())).isEqualTo(1);
    }

    @Test
    void terminalClaimsAreLeaseFencedAndAcknowledgeExactlyOnce() {
        Fixture fixture = fixture("claim");
        BookingAvailabilitySearchStore.SearchJob job =
                inContext(fixture.context(), () -> complete(fixture, "search-claim"));

        BookingAvailabilitySearchStore.TerminalClaim first =
                inContext(
                        fixture.context(),
                        () -> store.claimTerminalResults(1, Duration.ofMinutes(1)).getFirst());
        assertThat(inContext(
                        fixture.context(),
                        () -> store.claimTerminalResults(1, Duration.ofMinutes(1))))
                .isEmpty();
        jdbc.update(
                """
                UPDATE booking_search_terminal_outbox
                SET claim_until = ?
                WHERE id = ?
                """,
                Timestamp.from(Instant.EPOCH),
                first.outboxId());
        BookingAvailabilitySearchStore.TerminalClaim reclaimed =
                inContext(
                        fixture.context(),
                        () -> store.claimTerminalResults(1, Duration.ofMinutes(1)).getFirst());
        assertThat(reclaimed.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(inContext(
                        fixture.context(),
                        () -> store.acknowledgeTerminal(first.outboxId(), first.claimToken())))
                .isFalse();
        assertThat(inContext(
                        fixture.context(),
                        () ->
                                store.acknowledgeTerminal(
                                        reclaimed.outboxId(), reclaimed.claimToken())))
                .isTrue();
        assertThat(inContext(
                        fixture.context(),
                        () ->
                                store.acknowledgeTerminal(
                                        reclaimed.outboxId(), reclaimed.claimToken())))
                .isFalse();
        assertThat(first.searchJobId()).isEqualTo(job.searchJobId());
    }

    @Test
    void terminalMismatchRollsBackCandidateProgressAndOutboxTogether() {
        Fixture fixture = fixture("atomic");
        BookingAvailabilitySearchStore.SearchJob job =
                inContext(fixture.context(), () -> {
                    BookingAvailabilitySearchStore.SearchJob started =
                            store.startSearch("search-atomic");
                    store.progressSink(started.searchJobId())
                            .emit(AvailabilitySearchProgress.started());
                    store.progressSink(started.searchJobId())
                            .emit(AvailabilitySearchProgress.sourceCompleted(
                                    1, fixture.result().sourceOutcomes().getFirst()));
                    return started;
                });
        AvailabilitySearchResult mismatched =
                new AvailabilitySearchResult(
                        AvailabilitySearchStatus.COMPLETE,
                        List.of(fixture.candidate()),
                        fixture.result().recommendations(),
                        List.of(new AvailabilitySourceOutcome(
                                "source-b", AvailabilitySourceStatus.SUCCESS, 1)));

        assertThatThrownBy(() -> inContext(fixture.context(), () -> {
                    store.terminalSink(job.searchJobId()).emit(mismatched);
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(countByJob("booking_search_candidate", job.searchJobId())).isZero();
        assertThat(countByJob("booking_search_progress", job.searchJobId())).isEqualTo(2);
        assertThat(countByJob("booking_search_terminal_outbox", job.searchJobId())).isZero();
        assertThat(inContext(
                                fixture.context(),
                                () -> store.loadSearch(job.searchJobId()).orElseThrow().status()))
                .isEqualTo(BookingAvailabilitySearchStore.SearchJobStatus.STARTED);
    }

    @Test
    void cleanupDeletesOnlyExpiredUnselectedSearches() {
        Fixture fixture = fixture("retention");
        BookingAvailabilitySearchStore.SearchJob unselected =
                inContext(fixture.context(), () -> complete(fixture, "search-unselected"));
        BookingAvailabilitySearchStore.SearchJob selected =
                inContext(fixture.context(), () -> complete(fixture, "search-selected"));

        inContext(fixture.context(), () -> {
            OfferSnapshot wrongInventory =
                    new OfferSnapshot(
                            UUID.randomUUID(),
                            fixture.offer().provider(),
                            fixture.offer().environment(),
                            "different-inventory",
                            fixture.offer().retrievedAt(),
                            fixture.offer().expiresAt(),
                            fixture.offer().totalPrice(),
                            fixture.offer().currency(),
                            fixture.offer().travellerIds(),
                            fixture.offer().termsFingerprint(),
                            fixture.offer().nonRefundable(),
                            fixture.offer().available(),
                            fixture.offer().capabilities());
            executionStore.saveOffer(wrongInventory);
            assertThatThrownBy(
                            () ->
                                    store.bindOffer(
                                            selected.searchJobId(),
                                            fixture.candidate().candidateId(),
                                            wrongInventory.offerId()))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            executionStore.saveOffer(fixture.offer());
            store.bindOffer(
                    selected.searchJobId(),
                    fixture.candidate().candidateId(),
                    fixture.offer().offerId());
            return null;
        });
        jdbc.update(
                """
                UPDATE booking_search_job
                SET created_at = ?, retention_until = ?
                WHERE id IN (?, ?)
                """,
                Timestamp.from(Instant.EPOCH),
                Timestamp.from(Instant.EPOCH.plus(Duration.ofDays(1))),
                unselected.searchJobId(),
                selected.searchJobId());

        assertThat(inContext(
                        fixture.context(), () -> store.deleteExpiredUnselectedSearches(10)))
                .isEqualTo(1);
        assertThat(inContext(
                        fixture.context(), () -> store.loadSearch(unselected.searchJobId())))
                .isEmpty();
        assertThat(inContext(
                        fixture.context(), () -> store.loadSearch(selected.searchJobId())))
                .isPresent();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM booking_offer_snapshot WHERE id = ?",
                        Long.class,
                        fixture.offer().offerId()))
                .isEqualTo(1);
    }

    @Test
    void applicationFilterAndRlsHideAllSearchRowsFromPeerOutsiderAndSystem() {
        Fixture owner = fixture("owner");
        UUID peerId = UUID.randomUUID();
        seedPeer(peerId, owner.actorId(), owner.workspaceId(), "peer");
        Fixture outsider = fixture("outsider");
        BookingAvailabilitySearchStore.SearchJob job =
                inContext(owner.context(), () -> complete(owner, "search-owner"));

        assertThat(runtime(owner.context(), this::searchCounts))
                .containsExactly(1L, 1L, 3L, 1L);
        assertThat(runtime(context(peerId, owner.workspaceId()), this::searchCounts))
                .allMatch(count -> count == 0);
        assertThat(runtime(outsider.context(), this::searchCounts))
                .allMatch(count -> count == 0);
        assertThat(runtime(WorkspaceContext.system(), this::searchCounts))
                .allMatch(count -> count == 0);
        assertThat(inContext(
                        context(peerId, owner.workspaceId()),
                        () -> store.loadSearch(job.searchJobId())))
                .isEmpty();
        assertThatThrownBy(() -> inContext(
                        WorkspaceContext.system(), () -> store.loadSearch(job.searchJobId())))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> runtime(
                        context(peerId, owner.workspaceId()),
                        () -> jdbc.update(
                                """
                                INSERT INTO booking_search_job (
                                    id, idempotency_key, status, retention_until,
                                    created_at, updated_at, workspace_id, created_by_user_id)
                                VALUES (?, ?, 'STARTED', ?, ?, ?, ?, ?)
                                """,
                                UUID.randomUUID(),
                                "cross-actor-write",
                                Timestamp.from(clock.instant().plus(Duration.ofDays(7))),
                                Timestamp.from(clock.instant()),
                                Timestamp.from(clock.instant()),
                                owner.workspaceId(),
                                owner.actorId())))
                .isInstanceOf(DataAccessException.class);
    }

    private BookingAvailabilitySearchStore.SearchJob complete(
            Fixture fixture, String idempotencyKey) {
        BookingAvailabilitySearchStore.SearchJob job = store.startSearch(idempotencyKey);
        store.progressSink(job.searchJobId()).emit(AvailabilitySearchProgress.started());
        store.progressSink(job.searchJobId())
                .emit(AvailabilitySearchProgress.sourceCompleted(
                        1, fixture.result().sourceOutcomes().getFirst()));
        store.terminalSink(job.searchJobId()).emit(fixture.result());
        return job;
    }

    private List<Long> searchCounts() {
        return List.of(
                count("booking_search_job"),
                count("booking_search_candidate"),
                count("booking_search_progress"),
                count("booking_search_terminal_outbox"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long countById(String table, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);
    }

    private long countByJob(String table, UUID searchJobId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE search_job_id = ?",
                Long.class,
                searchJobId);
    }

    private Fixture fixture(String label) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        seed(actorId, workspaceId, label);
        AvailabilityCandidate candidate =
                new AvailabilityCandidate(
                        UUID.randomUUID(),
                        "source-a",
                        "inventory-" + label,
                        now.plus(Duration.ofHours(1)),
                        true,
                        AvailabilityFeasibility.FEASIBLE,
                        new BigDecimal("123.45"),
                        Currency.getInstance("USD"),
                        true,
                        0,
                        AvailabilityRisk.LOW,
                        AvailabilityRisk.MEDIUM,
                        1);
        AvailabilitySearchResult result =
                new AvailabilitySearchResult(
                        AvailabilitySearchStatus.COMPLETE,
                        List.of(candidate),
                        List.of(new RecommendedCandidate(
                                candidate, Set.of(RecommendationBadge.BEST_BALANCE))),
                        List.of(new AvailabilitySourceOutcome(
                                "source-a", AvailabilitySourceStatus.SUCCESS, 1)));
        OfferSnapshot offer =
                new OfferSnapshot(
                        UUID.randomUUID(),
                        "provider-a",
                        ProviderEnvironment.FAKE,
                        candidate.inventoryIdentity(),
                        now,
                        now.plus(Duration.ofHours(1)),
                        candidate.displayedPrice(),
                        candidate.currency(),
                        Set.of(UUID.randomUUID()),
                        "terms-digest-" + label,
                        false,
                        true,
                        Set.of(ProviderCapability.BOOK));
        return new Fixture(label, actorId, workspaceId, candidate, result, offer);
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }

    private void seedPeer(UUID actorId, UUID ownerId, UUID workspaceId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspaceId,
                actorId,
                ownerId);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private record Fixture(
            String label,
            UUID actorId,
            UUID workspaceId,
            AvailabilityCandidate candidate,
            AvailabilitySearchResult result,
            OfferSnapshot offer) {

        WorkspaceContext context() {
            return BookingAvailabilitySearchStoreIntegrationTest.context(actorId, workspaceId);
        }
    }
}
