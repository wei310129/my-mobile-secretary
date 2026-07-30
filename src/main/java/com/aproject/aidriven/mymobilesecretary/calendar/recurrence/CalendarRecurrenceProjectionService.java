package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarRecurrenceProjectionService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarRecurrenceProjectionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public SeriesAdoption adoptSeries(
            String requestId,
            UUID adoptionId,
            UUID planId,
            UUID seriesId,
            int expectedRuleRevision) {
        WorkspaceContext context = tenantContext();
        String requestHash = hash(requireText(requestId, "requestId"));
        String payloadHash = hash(String.join(
                "|",
                adoptionId.toString(),
                planId.toString(),
                seriesId.toString(),
                Integer.toString(expectedRuleRevision)));
        List<SeriesAdoption> replay = jdbc.query(
                """
                SELECT id, series_id, accepted_rule_revision
                FROM calendar_recurrence_adoption
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND operation_request_hash = ?
                """,
                (rows, row) -> {
                    requirePayload(
                            rows.getString("id"),
                            payloadHash,
                            "calendar_recurrence_adoption",
                            context,
                            requestHash);
                    return new SeriesAdoption(
                            rows.getObject("id", UUID.class),
                            rows.getObject("series_id", UUID.class),
                            rows.getInt("accepted_rule_revision"));
                },
                context.workspaceId(),
                context.actorId(),
                requestHash);
        if (!replay.isEmpty()) {
            return replay.getFirst();
        }

        Source source = jdbc.query(
                        """
                        SELECT series.source_created_by_user_id,
                               series.active_revision
                        FROM calendar_recurrence_series series
                        JOIN calendar_adoption adoption
                          ON adoption.id = ?
                         AND adoption.plan_id = series.plan_id
                         AND adoption.workspace_id = series.workspace_id
                         AND adoption.created_by_user_id = ?
                         AND adoption.source_created_by_user_id =
                             series.source_created_by_user_id
                         AND adoption.status = 'ACTIVE'
                        WHERE series.id = ? AND series.plan_id = ?
                          AND series.workspace_id = ?
                        """,
                        (rows, row) -> new Source(
                                rows.getObject("source_created_by_user_id", UUID.class),
                                rows.getInt("active_revision")),
                        adoptionId,
                        context.actorId(),
                        seriesId,
                        planId,
                        context.workspaceId())
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new NotFoundException("Calendar recurrence series", seriesId));
        if (source.activeRevision() != expectedRuleRevision) {
            throw new BusinessException(
                    "STALE_RECURRENCE_REVISION",
                    "Calendar recurrence changed; review the new revision");
        }

        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_adoption (
                    id, adoption_id, plan_id, series_id,
                    accepted_rule_revision, adoption_scope,
                    adoption_status, adoption_revision,
                    operation_request_hash, operation_payload_hash,
                    created_at, updated_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, 'SERIES', 'ACTIVE', 1,
                    ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                adoptionId,
                planId,
                seriesId,
                expectedRuleRevision,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                source.sourceOwnerId());
        return new SeriesAdoption(id, seriesId, expectedRuleRevision);
    }

    @Transactional
    public OccurrenceParticipation setOccurrenceParticipation(
            String requestId,
            UUID planId,
            UUID seriesId,
            int acceptedRuleRevision,
            CalendarOccurrenceKey key,
            boolean skipped) {
        WorkspaceContext context = tenantContext();
        String requestHash = hash(requireText(requestId, "requestId"));
        String payloadHash = hash(String.join(
                "|",
                planId.toString(),
                seriesId.toString(),
                Integer.toString(acceptedRuleRevision),
                key.toString(),
                Boolean.toString(skipped)));
        List<OccurrenceParticipation> replay = jdbc.query(
                """
                SELECT id, exception_state, exception_revision,
                       operation_payload_hash
                FROM calendar_recurrence_participation_exception
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND operation_request_hash = ?
                """,
                (rows, row) -> {
                    if (!payloadHash.equals(rows.getString("operation_payload_hash"))) {
                        throw conflictingReplay();
                    }
                    return new OccurrenceParticipation(
                            rows.getObject("id", UUID.class),
                            "SKIPPED".equals(rows.getString("exception_state")),
                            rows.getLong("exception_revision"));
                },
                context.workspaceId(),
                context.actorId(),
                requestHash);
        if (!replay.isEmpty()) {
            return replay.getFirst();
        }

        Source source = jdbc.query(
                        """
                        SELECT adoption.source_created_by_user_id,
                               series.active_revision
                        FROM calendar_recurrence_adoption adoption
                        JOIN calendar_recurrence_series series
                          ON series.id = adoption.series_id
                         AND series.workspace_id = adoption.workspace_id
                         AND series.source_created_by_user_id =
                             adoption.source_created_by_user_id
                        WHERE adoption.series_id = ?
                          AND adoption.accepted_rule_revision = ?
                          AND adoption.adoption_scope = 'SERIES'
                          AND adoption.adoption_status = 'ACTIVE'
                          AND adoption.workspace_id = ?
                          AND adoption.created_by_user_id = ?
                          AND series.plan_id = ?
                        """,
                        (rows, row) -> new Source(
                                rows.getObject("source_created_by_user_id", UUID.class),
                                rows.getInt("active_revision")),
                        seriesId,
                        acceptedRuleRevision,
                        context.workspaceId(),
                        context.actorId(),
                        planId)
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new NotFoundException("Accepted recurrence series", seriesId));
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        int changed = jdbc.update(
                """
                INSERT INTO calendar_recurrence_participation_exception (
                    id, plan_id, series_id, accepted_rule_revision,
                    logical_timed_start, logical_all_day_start,
                    exception_state, exception_revision,
                    operation_request_hash, operation_payload_hash,
                    created_at, updated_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    series_id, accepted_rule_revision,
                    COALESCE(logical_timed_start, '0001-01-01 00:00:00'::timestamp),
                    COALESCE(logical_all_day_start, '0001-01-01'::date),
                    workspace_id, created_by_user_id)
                DO UPDATE SET
                    exception_state = EXCLUDED.exception_state,
                    exception_revision =
                        calendar_recurrence_participation_exception.exception_revision + 1,
                    operation_request_hash = EXCLUDED.operation_request_hash,
                    operation_payload_hash = EXCLUDED.operation_payload_hash,
                    updated_at = EXCLUDED.updated_at
                """,
                id,
                planId,
                seriesId,
                acceptedRuleRevision,
                key.timed() ? Timestamp.valueOf(key.timedStart()) : null,
                key.timed() ? null : java.sql.Date.valueOf(key.allDayStart()),
                skipped ? "SKIPPED" : "RESTORED",
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                source.sourceOwnerId());
        if (changed != 1) {
            throw new IllegalStateException("Occurrence participation did not converge");
        }
        return currentOccurrence(seriesId, acceptedRuleRevision, key, context);
    }

    private OccurrenceParticipation currentOccurrence(
            UUID seriesId,
            int revision,
            CalendarOccurrenceKey key,
            WorkspaceContext context) {
        return jdbc.query(
                        """
                        SELECT id, exception_state, exception_revision
                        FROM calendar_recurrence_participation_exception
                        WHERE series_id = ? AND accepted_rule_revision = ?
                          AND logical_timed_start IS NOT DISTINCT FROM ?
                          AND logical_all_day_start IS NOT DISTINCT FROM ?
                          AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        (rows, row) -> new OccurrenceParticipation(
                                rows.getObject("id", UUID.class),
                                "SKIPPED".equals(rows.getString("exception_state")),
                                rows.getLong("exception_revision")),
                        seriesId,
                        revision,
                        key.timed() ? Timestamp.valueOf(key.timedStart()) : null,
                        key.timed() ? null : java.sql.Date.valueOf(key.allDayStart()),
                        context.workspaceId(),
                        context.actorId())
                .stream()
                .findFirst()
                .orElseThrow();
    }

    private void requirePayload(
            String id,
            String payloadHash,
            String table,
            WorkspaceContext context,
            String requestHash) {
        String stored = jdbc.queryForObject(
                "SELECT operation_payload_hash FROM "
                        + table
                        + " WHERE id = ? AND workspace_id = ?"
                        + " AND created_by_user_id = ? AND operation_request_hash = ?",
                String.class,
                UUID.fromString(id),
                context.workspaceId(),
                context.actorId(),
                requestHash);
        if (!payloadHash.equals(stored)) {
            throw conflictingReplay();
        }
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Actor and workspace context are required");
        }
        return context;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static BusinessException conflictingReplay() {
        return new BusinessException(
                "CONFLICTING_RECURRENCE_REPLAY",
                "The request id was already used for another recurrence change");
    }

    private record Source(UUID sourceOwnerId, int activeRevision) {}

    public record SeriesAdoption(UUID id, UUID seriesId, int acceptedRuleRevision) {
        public SeriesAdoption {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(seriesId, "seriesId");
        }
    }

    public record OccurrenceParticipation(UUID id, boolean skipped, long revision) {
        public OccurrenceParticipation {
            Objects.requireNonNull(id, "id");
        }
    }
}
