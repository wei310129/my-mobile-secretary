package com.aproject.aidriven.mymobilesecretary.booking.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityCandidate;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityFeasibility;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityProgressSink;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityRisk;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySearchProgress;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySearchResult;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySearchStatus;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySourceOutcome;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilitySourceStatus;
import com.aproject.aidriven.mymobilesecretary.booking.availability.AvailabilityTerminalSink;
import com.aproject.aidriven.mymobilesecretary.booking.availability.RecommendationBadge;
import com.aproject.aidriven.mymobilesecretary.booking.availability.RecommendedCandidate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class BookingAvailabilitySearchStore {

    private static final Duration UNSELECTED_RETENTION = Duration.ofDays(7);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 160;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public BookingAvailabilitySearchStore(
            JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public SearchJob startSearch(String idempotencyKey) {
        requireText(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey is too long");
        }
        WorkspaceContext owner = owner();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUID searchJobId = UUID.randomUUID();
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO booking_search_job (
                            id, idempotency_key, status, retention_until,
                            created_at, updated_at, workspace_id, created_by_user_id)
                        VALUES (?, ?, 'STARTED', ?, ?, ?, ?, ?)
                        ON CONFLICT (
                            workspace_id, created_by_user_id, idempotency_key)
                        DO NOTHING
                        """,
                        searchJobId,
                        idempotencyKey,
                        Timestamp.from(now.plus(UNSELECTED_RETENTION)),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        owner.workspaceId(),
                        owner.actorId());
        if (inserted == 1) {
            return new SearchJob(
                    searchJobId, idempotencyKey, now.plus(UNSELECTED_RETENTION));
        }
        return jdbc.query(
                        """
                        SELECT id, idempotency_key, retention_until
                        FROM booking_search_job
                        WHERE workspace_id = ? AND created_by_user_id = ?
                          AND idempotency_key = ?
                        """,
                        (result, row) ->
                                new SearchJob(
                                        result.getObject("id", UUID.class),
                                        result.getString("idempotency_key"),
                                        result.getTimestamp("retention_until").toInstant()),
                        owner.workspaceId(),
                        owner.actorId(),
                        idempotencyKey)
                .stream()
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "search idempotency conflict was not durable"));
    }

    public AvailabilityProgressSink progressSink(UUID searchJobId) {
        Objects.requireNonNull(searchJobId, "searchJobId");
        return progress -> {
            Objects.requireNonNull(progress, "progress");
            if (progress.stage() == AvailabilitySearchProgress.Stage.TERMINAL) {
                return;
            }
            transactions.executeWithoutResult(
                    ignored -> appendProgress(searchJobId, progress));
        };
    }

    public AvailabilityTerminalSink terminalSink(UUID searchJobId) {
        Objects.requireNonNull(searchJobId, "searchJobId");
        return result ->
                transactions.executeWithoutResult(
                        ignored -> recordTerminal(searchJobId, result));
    }

    @Transactional(readOnly = true)
    public Optional<SearchView> loadSearch(UUID searchJobId) {
        Objects.requireNonNull(searchJobId, "searchJobId");
        WorkspaceContext owner = owner();
        Optional<JobRow> job =
                jdbc.query(
                                """
                                SELECT id, idempotency_key, status, retention_until, terminal_at
                                FROM booking_search_job
                                WHERE id = ? AND workspace_id = ?
                                  AND created_by_user_id = ?
                                """,
                                (result, row) ->
                                        new JobRow(
                                                result.getObject("id", UUID.class),
                                                result.getString("idempotency_key"),
                                                SearchJobStatus.valueOf(
                                                        result.getString("status")),
                                                result.getTimestamp("retention_until").toInstant(),
                                                optionalInstant(result, "terminal_at")),
                                searchJobId,
                                owner.workspaceId(),
                                owner.actorId())
                        .stream()
                        .findFirst();
        if (job.isEmpty()) {
            return Optional.empty();
        }
        List<CandidateRow> candidateRows =
                jdbc.query(
                        """
                        SELECT candidate_id, source_key, inventory_identity, expires_at,
                               available, feasibility, displayed_price, currency,
                               total_fully_known, required_add_on_count, refund_risk,
                               change_risk, restriction_count, recommendation_badges
                        FROM booking_search_candidate
                        WHERE search_job_id = ? AND workspace_id = ?
                          AND created_by_user_id = ?
                        ORDER BY source_key, inventory_identity, candidate_id
                        """,
                        (result, row) ->
                                new CandidateRow(
                                        mapCandidate(result),
                                        decodeBadges(
                                                result.getString("recommendation_badges"))),
                        searchJobId,
                        owner.workspaceId(),
                        owner.actorId());
        List<ProgressRow> progressRows = loadProgressRows(searchJobId, owner);
        List<AvailabilityCandidate> candidates =
                candidateRows.stream().map(CandidateRow::candidate).toList();
        List<RecommendedCandidate> recommendations =
                candidateRows.stream()
                        .filter(row -> !row.badges().isEmpty())
                        .map(row -> new RecommendedCandidate(row.candidate(), row.badges()))
                        .toList();
        List<AvailabilitySourceOutcome> outcomes =
                progressRows.stream()
                        .filter(row -> row.candidateCount() != null)
                        .map(
                                row ->
                                        new AvailabilitySourceOutcome(
                                                row.progress().sourceKey().orElseThrow(),
                                                row.progress().sourceStatus().orElseThrow(),
                                                row.candidateCount()))
                        .toList();
        JobRow row = job.orElseThrow();
        return Optional.of(
                new SearchView(
                        row.searchJobId(),
                        row.idempotencyKey(),
                        row.status(),
                        candidates,
                        recommendations,
                        outcomes,
                        progressRows.stream().map(ProgressRow::progress).toList(),
                        row.retentionUntil(),
                        row.terminalAt()));
    }

    @Transactional
    public void bindOffer(UUID searchJobId, UUID candidateId, UUID offerId) {
        Objects.requireNonNull(searchJobId, "searchJobId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(offerId, "offerId");
        WorkspaceContext owner = owner();
        int updated =
                jdbc.update(
                        """
                        UPDATE booking_search_candidate candidate
                        SET offer_id = ?
                        WHERE candidate.search_job_id = ?
                          AND candidate.candidate_id = ?
                          AND candidate.workspace_id = ?
                          AND candidate.created_by_user_id = ?
                          AND (candidate.offer_id IS NULL OR candidate.offer_id = ?)
                          AND EXISTS (
                              SELECT 1
                              FROM booking_offer_snapshot offer
                              WHERE offer.id = ?
                                AND offer.workspace_id = candidate.workspace_id
                                AND offer.created_by_user_id =
                                    candidate.created_by_user_id
                                AND offer.inventory_identity =
                                    candidate.inventory_identity)
                        """,
                        offerId,
                        searchJobId,
                        candidateId,
                        owner.workspaceId(),
                        owner.actorId(),
                        offerId,
                        offerId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "candidate or actor-owned offer is unavailable, or binding conflicts");
        }
    }

    @Transactional
    public int deleteExpiredUnselectedSearches(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        WorkspaceContext owner = owner();
        List<UUID> expired =
                jdbc.query(
                        """
                        SELECT job.id
                        FROM booking_search_job job
                        WHERE job.workspace_id = ? AND job.created_by_user_id = ?
                          AND job.retention_until <= ?
                          AND NOT EXISTS (
                              SELECT 1
                              FROM booking_search_candidate candidate
                              WHERE candidate.search_job_id = job.id
                                AND candidate.workspace_id = job.workspace_id
                                AND candidate.created_by_user_id =
                                    job.created_by_user_id
                                AND candidate.offer_id IS NOT NULL)
                        ORDER BY job.retention_until, job.id
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?
                        """,
                        (result, row) -> result.getObject("id", UUID.class),
                        owner.workspaceId(),
                        owner.actorId(),
                        Timestamp.from(clock.instant()),
                        limit);
        int deleted = 0;
        for (UUID searchJobId : expired) {
            deleted +=
                    jdbc.update(
                            """
                            DELETE FROM booking_search_job
                            WHERE id = ? AND workspace_id = ?
                              AND created_by_user_id = ?
                            """,
                            searchJobId,
                            owner.workspaceId(),
                            owner.actorId());
        }
        return deleted;
    }

    @Transactional
    public List<TerminalClaim> claimTerminalResults(
            int limit, Duration leaseDuration) {
        if (limit < 1
                || leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()) {
            throw new IllegalArgumentException(
                    "positive limit and leaseDuration are required");
        }
        WorkspaceContext owner = owner();
        Instant now = clock.instant();
        List<UUID> ids =
                jdbc.query(
                        """
                        SELECT id
                        FROM booking_search_terminal_outbox
                        WHERE workspace_id = ? AND created_by_user_id = ?
                          AND (
                            status = 'PENDING'
                            OR (status = 'CLAIMED' AND claim_until <= ?))
                        ORDER BY created_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT ?
                        """,
                        (result, row) -> result.getObject("id", UUID.class),
                        owner.workspaceId(),
                        owner.actorId(),
                        Timestamp.from(now),
                        limit);
        List<TerminalClaim> claims = new ArrayList<>();
        for (UUID id : ids) {
            UUID token = UUID.randomUUID();
            Instant claimUntil = now.plus(leaseDuration);
            jdbc.update(
                    """
                    UPDATE booking_search_terminal_outbox
                    SET status = 'CLAIMED', claim_token = ?, claim_until = ?,
                        attempt_count = attempt_count + 1, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                    """,
                    token,
                    Timestamp.from(claimUntil),
                    Timestamp.from(now),
                    id,
                    owner.workspaceId(),
                    owner.actorId());
            claims.add(
                    jdbc.queryForObject(
                            """
                            SELECT id, search_job_id, event_key,
                                   result_status, candidate_count
                            FROM booking_search_terminal_outbox
                            WHERE id = ? AND workspace_id = ?
                              AND created_by_user_id = ?
                            """,
                            (result, row) ->
                                    new TerminalClaim(
                                            result.getObject("id", UUID.class),
                                            result.getObject(
                                                    "search_job_id", UUID.class),
                                            result.getString("event_key"),
                                            AvailabilitySearchStatus.valueOf(
                                                    result.getString(
                                                            "result_status")),
                                            result.getInt("candidate_count"),
                                            token,
                                            claimUntil),
                            id,
                            owner.workspaceId(),
                            owner.actorId()));
        }
        return List.copyOf(claims);
    }

    @Transactional
    public boolean acknowledgeTerminal(UUID outboxId, UUID claimToken) {
        Objects.requireNonNull(outboxId, "outboxId");
        Objects.requireNonNull(claimToken, "claimToken");
        WorkspaceContext owner = owner();
        Instant now = clock.instant();
        return jdbc.update(
                        """
                        UPDATE booking_search_terminal_outbox
                        SET status = 'ACKNOWLEDGED', acknowledged_at = ?,
                            claim_token = NULL, claim_until = NULL, updated_at = ?
                        WHERE id = ? AND claim_token = ? AND status = 'CLAIMED'
                          AND claim_until > ?
                          AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        outboxId,
                        claimToken,
                        Timestamp.from(now),
                        owner.workspaceId(),
                        owner.actorId())
                == 1;
    }

    private void appendProgress(
            UUID searchJobId, AvailabilitySearchProgress progress) {
        WorkspaceContext owner = owner();
        requireStartedJob(searchJobId, owner);
        Instant now = clock.instant();
        String sourceKey = progress.sourceKey().orElse(null);
        String sourceStatus =
                progress.sourceStatus().map(Enum::name).orElse(null);
        int inserted =
                jdbc.update(
                        """
                        INSERT INTO booking_search_progress (
                            id, search_job_id, sequence, stage, source_key,
                            source_status, candidate_count, created_at,
                            workspace_id, created_by_user_id)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                        ON CONFLICT (
                            search_job_id, sequence, workspace_id,
                            created_by_user_id)
                        DO NOTHING
                        """,
                        UUID.randomUUID(),
                        searchJobId,
                        progress.sequence(),
                        progress.stage().name(),
                        sourceKey,
                        sourceStatus,
                        Timestamp.from(now),
                        owner.workspaceId(),
                        owner.actorId());
        if (inserted == 0) {
            ProgressRow saved =
                    loadProgressRows(searchJobId, owner).stream()
                            .filter(
                                    row ->
                                            row.progress().sequence()
                                                    == progress.sequence())
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "progress conflict was not durable"));
            if (!saved.progress().equals(progress)) {
                throw new IllegalStateException(
                        "progress replay changes durable event");
            }
        }
        jdbc.update(
                """
                UPDATE booking_search_job
                SET updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                Timestamp.from(now),
                searchJobId,
                owner.workspaceId(),
                owner.actorId());
    }

    private void recordTerminal(
            UUID searchJobId, AvailabilitySearchResult result) {
        Objects.requireNonNull(result, "result");
        validateTerminalResult(result);
        WorkspaceContext owner = owner();
        String fingerprint = fingerprint(result);
        TerminalState terminalState =
                jdbc.query(
                                """
                                SELECT status, terminal_fingerprint
                                FROM booking_search_job
                                WHERE id = ? AND workspace_id = ?
                                  AND created_by_user_id = ?
                                FOR UPDATE
                                """,
                                (row, index) ->
                                        new TerminalState(
                                                row.getString("status"),
                                                row.getString(
                                                        "terminal_fingerprint")),
                                searchJobId,
                                owner.workspaceId(),
                                owner.actorId())
                        .stream()
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "search job is unavailable to actor"));
        if (terminalState.fingerprint() != null) {
            if (terminalState.fingerprint().equals(fingerprint)) {
                return;
            }
            throw new IllegalStateException(
                    "terminal replay changes durable search result");
        }
        if (!terminalState.status().equals("STARTED")) {
            throw new IllegalStateException("search job terminal state is invalid");
        }
        Long started =
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM booking_search_progress
                        WHERE search_job_id = ? AND sequence = 0
                          AND stage = 'STARTED' AND workspace_id = ?
                          AND created_by_user_id = ?
                        """,
                        Long.class,
                        searchJobId,
                        owner.workspaceId(),
                        owner.actorId());
        if (started == null || started != 1) {
            throw new IllegalStateException(
                    "terminal result requires durable started progress");
        }

        Map<UUID, Set<RecommendationBadge>> badges =
                recommendationBadges(result);
        Instant now = clock.instant();
        for (AvailabilityCandidate candidate : result.eligibleCandidates()) {
            insertCandidate(
                    searchJobId,
                    candidate,
                    badges.getOrDefault(candidate.candidateId(), Set.of()),
                    owner,
                    now);
        }
        for (AvailabilitySourceOutcome outcome : result.sourceOutcomes()) {
            int updated =
                    jdbc.update(
                            """
                            UPDATE booking_search_progress
                            SET candidate_count = ?
                            WHERE search_job_id = ? AND stage = 'SOURCE_COMPLETED'
                              AND source_key = ? AND source_status = ?
                              AND workspace_id = ? AND created_by_user_id = ?
                            """,
                            outcome.receivedCandidateCount(),
                            searchJobId,
                            outcome.sourceKey(),
                            outcome.status().name(),
                            owner.workspaceId(),
                            owner.actorId());
            if (updated != 1) {
                throw new IllegalStateException(
                        "terminal source outcome does not match durable progress");
            }
        }
        Long terminalSequence =
                jdbc.queryForObject(
                        """
                        SELECT coalesce(max(sequence), -1) + 1
                        FROM booking_search_progress
                        WHERE search_job_id = ? AND workspace_id = ?
                          AND created_by_user_id = ?
                        """,
                        Long.class,
                        searchJobId,
                        owner.workspaceId(),
                        owner.actorId());
        jdbc.update(
                """
                INSERT INTO booking_search_progress (
                    id, search_job_id, sequence, stage, source_key,
                    source_status, candidate_count, created_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, 'TERMINAL', NULL, NULL, NULL, ?, ?, ?)
                """,
                UUID.randomUUID(),
                searchJobId,
                terminalSequence,
                Timestamp.from(now),
                owner.workspaceId(),
                owner.actorId());
        jdbc.update(
                """
                UPDATE booking_search_job
                SET status = ?, terminal_fingerprint = ?, terminal_at = ?,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                result.status().name(),
                fingerprint,
                Timestamp.from(now),
                Timestamp.from(now),
                searchJobId,
                owner.workspaceId(),
                owner.actorId());
        jdbc.update(
                """
                INSERT INTO booking_search_terminal_outbox (
                    id, search_job_id, event_key, result_status,
                    candidate_count, status, claim_token, claim_until,
                    attempt_count, created_at, updated_at, acknowledged_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'PENDING', NULL, NULL, 0, ?, ?, NULL, ?, ?)
                """,
                UUID.randomUUID(),
                searchJobId,
                "booking.search.terminal:" + searchJobId,
                result.status().name(),
                result.eligibleCandidates().size(),
                Timestamp.from(now),
                Timestamp.from(now),
                owner.workspaceId(),
                owner.actorId());
    }

    private void insertCandidate(
            UUID searchJobId,
            AvailabilityCandidate candidate,
            Set<RecommendationBadge> badges,
            WorkspaceContext owner,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO booking_search_candidate (
                    id, search_job_id, candidate_id, source_key,
                    inventory_identity, expires_at, available, feasibility,
                    displayed_price, currency, total_fully_known,
                    required_add_on_count, refund_risk, change_risk,
                    restriction_count, recommendation_badges, offer_id,
                    created_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
                """,
                UUID.randomUUID(),
                searchJobId,
                candidate.candidateId(),
                candidate.sourceKey(),
                candidate.inventoryIdentity(),
                Timestamp.from(candidate.expiresAt()),
                candidate.available(),
                candidate.feasibility().name(),
                candidate.displayedPrice(),
                candidate.currency().getCurrencyCode(),
                candidate.totalFullyKnown(),
                candidate.requiredAddOnCount(),
                candidate.refundRisk().name(),
                candidate.changeRisk().name(),
                candidate.restrictionCount(),
                encodeBadges(badges),
                Timestamp.from(now),
                owner.workspaceId(),
                owner.actorId());
    }

    private void requireStartedJob(UUID searchJobId, WorkspaceContext owner) {
        Long count =
                jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM booking_search_job
                        WHERE id = ? AND status = 'STARTED'
                          AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        Long.class,
                        searchJobId,
                        owner.workspaceId(),
                        owner.actorId());
        if (count == null || count != 1) {
            throw new IllegalStateException(
                    "started search job is unavailable to actor");
        }
    }

    private List<ProgressRow> loadProgressRows(
            UUID searchJobId, WorkspaceContext owner) {
        return jdbc.query(
                """
                SELECT sequence, stage, source_key, source_status,
                       candidate_count
                FROM booking_search_progress
                WHERE search_job_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                ORDER BY sequence
                """,
                (result, row) -> {
                    String sourceKey = result.getString("source_key");
                    String sourceStatus = result.getString("source_status");
                    Integer candidateCount =
                            result.getObject("candidate_count", Integer.class);
                    return new ProgressRow(
                            new AvailabilitySearchProgress(
                                    result.getLong("sequence"),
                                    AvailabilitySearchProgress.Stage.valueOf(
                                            result.getString("stage")),
                                    Optional.ofNullable(sourceKey),
                                    Optional.ofNullable(sourceStatus)
                                            .map(AvailabilitySourceStatus::valueOf)),
                            candidateCount);
                },
                searchJobId,
                owner.workspaceId(),
                owner.actorId());
    }

    private static AvailabilityCandidate mapCandidate(ResultSet result)
            throws SQLException {
        return new AvailabilityCandidate(
                result.getObject("candidate_id", UUID.class),
                result.getString("source_key"),
                result.getString("inventory_identity"),
                result.getTimestamp("expires_at").toInstant(),
                result.getBoolean("available"),
                AvailabilityFeasibility.valueOf(result.getString("feasibility")),
                result.getBigDecimal("displayed_price"),
                Currency.getInstance(result.getString("currency")),
                result.getBoolean("total_fully_known"),
                result.getInt("required_add_on_count"),
                AvailabilityRisk.valueOf(result.getString("refund_risk")),
                AvailabilityRisk.valueOf(result.getString("change_risk")),
                result.getInt("restriction_count"));
    }

    private static Map<UUID, Set<RecommendationBadge>> recommendationBadges(
            AvailabilitySearchResult result) {
        Set<UUID> candidateIds =
                result.eligibleCandidates().stream()
                        .map(AvailabilityCandidate::candidateId)
                        .collect(Collectors.toSet());
        if (candidateIds.size() != result.eligibleCandidates().size()) {
            throw new IllegalArgumentException(
                    "terminal candidates must have unique identities");
        }
        Map<UUID, Set<RecommendationBadge>> badges = new HashMap<>();
        for (RecommendedCandidate recommendation : result.recommendations()) {
            UUID candidateId = recommendation.candidate().candidateId();
            if (!candidateIds.contains(candidateId)
                    || !result.eligibleCandidates().contains(
                            recommendation.candidate())
                    || badges.put(candidateId, recommendation.badges()) != null) {
                throw new IllegalArgumentException(
                        "recommendation must bind one durable candidate");
            }
        }
        return Map.copyOf(badges);
    }

    private static void validateTerminalResult(
            AvailabilitySearchResult result) {
        Set<String> sourceKeys = new HashSet<>();
        for (AvailabilitySourceOutcome outcome : result.sourceOutcomes()) {
            if (!sourceKeys.add(outcome.sourceKey())) {
                throw new IllegalArgumentException(
                        "terminal source outcomes must be unique");
            }
        }
        recommendationBadges(result);
    }

    private static String fingerprint(AvailabilitySearchResult result) {
        StringBuilder canonical = new StringBuilder(result.status().name());
        result.eligibleCandidates().stream()
                .sorted(Comparator.comparing(candidate -> candidate.candidateId().toString()))
                .forEach(
                        candidate ->
                                canonical.append("|candidate:")
                                        .append(candidate.candidateId())
                                        .append(':')
                                        .append(candidate.sourceKey())
                                        .append(':')
                                        .append(candidate.inventoryIdentity())
                                        .append(':')
                                        .append(candidate.expiresAt())
                                        .append(':')
                                        .append(candidate.available())
                                        .append(':')
                                        .append(candidate.feasibility())
                                        .append(':')
                                        .append(candidate.displayedPrice().toPlainString())
                                        .append(':')
                                        .append(candidate.currency().getCurrencyCode())
                                        .append(':')
                                        .append(candidate.totalFullyKnown())
                                        .append(':')
                                        .append(candidate.requiredAddOnCount())
                                        .append(':')
                                        .append(candidate.refundRisk())
                                        .append(':')
                                        .append(candidate.changeRisk())
                                        .append(':')
                                        .append(candidate.restrictionCount()));
        result.recommendations().stream()
                .sorted(
                        Comparator.comparing(
                                recommendation ->
                                        recommendation
                                                .candidate()
                                                .candidateId()
                                                .toString()))
                .forEach(
                        recommendation ->
                                canonical.append("|recommendation:")
                                        .append(
                                                recommendation
                                                        .candidate()
                                                        .candidateId())
                                        .append(':')
                                        .append(
                                                encodeBadges(
                                                        recommendation.badges())));
        result.sourceOutcomes().stream()
                .sorted(Comparator.comparing(AvailabilitySourceOutcome::sourceKey))
                .forEach(
                        outcome ->
                                canonical.append("|source:")
                                        .append(outcome.sourceKey())
                                        .append(':')
                                        .append(outcome.status())
                                        .append(':')
                                        .append(outcome.receivedCandidateCount()));
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            canonical.toString()
                                                    .getBytes(
                                                            StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String encodeBadges(Set<RecommendationBadge> badges) {
        return badges.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static Set<RecommendationBadge> decodeBadges(String encoded) {
        EnumSet<RecommendationBadge> badges =
                EnumSet.noneOf(RecommendationBadge.class);
        if (encoded != null && !encoded.isBlank()) {
            for (String badge : encoded.split(",")) {
                badges.add(RecommendationBadge.valueOf(badge));
            }
        }
        return Set.copyOf(badges);
    }

    private static Optional<Instant> optionalInstant(
            ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null
                ? Optional.empty()
                : Optional.of(timestamp.toInstant());
    }

    private static WorkspaceContext owner() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("tenant actor context is required");
        }
        return context;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record SearchJob(
            UUID searchJobId, String idempotencyKey, Instant retentionUntil) {}

    public record SearchView(
            UUID searchJobId,
            String idempotencyKey,
            SearchJobStatus status,
            List<AvailabilityCandidate> candidates,
            List<RecommendedCandidate> recommendations,
            List<AvailabilitySourceOutcome> sourceOutcomes,
            List<AvailabilitySearchProgress> progress,
            Instant retentionUntil,
            Optional<Instant> terminalAt) {

        public SearchView {
            candidates = List.copyOf(candidates);
            recommendations = List.copyOf(recommendations);
            sourceOutcomes = List.copyOf(sourceOutcomes);
            progress = List.copyOf(progress);
            terminalAt = Objects.requireNonNull(terminalAt, "terminalAt");
        }
    }

    public record TerminalClaim(
            UUID outboxId,
            UUID searchJobId,
            String eventKey,
            AvailabilitySearchStatus resultStatus,
            int candidateCount,
            UUID claimToken,
            Instant claimUntil) {}

    public enum SearchJobStatus {
        STARTED,
        COMPLETE,
        PARTIAL,
        NO_RESULTS,
        FAILED
    }

    private record JobRow(
            UUID searchJobId,
            String idempotencyKey,
            SearchJobStatus status,
            Instant retentionUntil,
            Optional<Instant> terminalAt) {}

    private record TerminalState(String status, String fingerprint) {}

    private record CandidateRow(
            AvailabilityCandidate candidate, Set<RecommendationBadge> badges) {}

    private record ProgressRow(
            AvailabilitySearchProgress progress, Integer candidateCount) {}
}
