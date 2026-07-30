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
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_adoption_rule_snapshot (
                    recurrence_adoption_id, plan_id, activity_id,
                    projection_title, series_id,
                    rule_revision, frequency, recurrence_interval,
                    weekdays, week_start, month_day, weekday_ordinal,
                    weekday, year_month, year_day, timed_anchor,
                    duration_seconds, zone_id, all_day_anchor,
                    all_day_span, end_kind, occurrence_count,
                    until_date, until_timed, effective_from_timed,
                    effective_from_date, effective_until_timed,
                    effective_until_date, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                SELECT ?, ?, series.activity_id,
                       COALESCE(activity.title, plan.title),
                       revision.series_id, revision.revision,
                       revision.frequency, revision.recurrence_interval,
                       revision.weekdays, revision.week_start,
                       revision.month_day, revision.weekday_ordinal,
                       revision.weekday, revision.year_month,
                       revision.year_day, revision.timed_anchor,
                       revision.duration_seconds, revision.zone_id,
                       revision.all_day_anchor, revision.all_day_span,
                       revision.end_kind, revision.occurrence_count,
                       revision.until_date, revision.until_timed,
                       revision.effective_from_timed,
                       revision.effective_from_date,
                       revision.effective_until_timed,
                       revision.effective_until_date,
                       ?, revision.workspace_id, ?, ?
                FROM calendar_recurrence_rule_revision revision
                JOIN calendar_recurrence_series series
                  ON series.id = revision.series_id
                 AND series.workspace_id = revision.workspace_id
                 AND series.source_created_by_user_id =
                        revision.source_created_by_user_id
                JOIN calendar_plan plan
                  ON plan.id = series.plan_id
                 AND plan.workspace_id = series.workspace_id
                 AND plan.created_by_user_id =
                        series.source_created_by_user_id
                LEFT JOIN calendar_activity activity
                  ON activity.id = series.activity_id
                 AND activity.plan_id = series.plan_id
                 AND activity.workspace_id = series.workspace_id
                 AND activity.created_by_user_id =
                        series.source_created_by_user_id
                WHERE revision.series_id = ? AND revision.revision = ?
                  AND revision.workspace_id = ?
                  AND revision.source_created_by_user_id = ?
                """,
                id,
                planId,
                Timestamp.from(now),
                context.actorId(),
                source.sourceOwnerId(),
                seriesId,
                expectedRuleRevision,
                context.workspaceId(),
                source.sourceOwnerId());
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_adoption_exception_snapshot (
                    recurrence_adoption_id, source_exception_id,
                    series_id, rule_revision, logical_timed_start,
                    logical_all_day_start, kind, placement_kind,
                    timed_start, timed_end, zone_id, all_day_start,
                    all_day_end_exclusive, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                SELECT ?, exception.id, exception.series_id,
                       exception.rule_revision,
                       exception.logical_timed_start,
                       exception.logical_all_day_start, exception.kind,
                       exception.placement_kind, exception.timed_start,
                       exception.timed_end, exception.zone_id,
                       exception.all_day_start,
                       exception.all_day_end_exclusive,
                       exception.workspace_id, ?, ?
                FROM calendar_recurrence_exception exception
                WHERE exception.series_id = ?
                  AND exception.rule_revision = ?
                  AND exception.workspace_id = ?
                  AND exception.source_created_by_user_id = ?
                """,
                id,
                context.actorId(),
                source.sourceOwnerId(),
                seriesId,
                expectedRuleRevision,
                context.workspaceId(),
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

    @Transactional
    public ReminderMaterializationResult persistReminderPlan(
            UUID planId,
            UUID seriesId,
            int recurrenceRuleRevision,
            Instant horizonExclusive,
            List<CalendarRecurringReminderPlanner.Materialization> planned) {
        WorkspaceContext context = tenantContext();
        Objects.requireNonNull(horizonExclusive, "horizonExclusive");
        Objects.requireNonNull(planned, "planned");
        if (planned.isEmpty()) {
            return new ReminderMaterializationResult(0, 0, horizonExclusive);
        }
        Source source = jdbc.query(
                        """
                        SELECT source_created_by_user_id, active_revision
                        FROM calendar_recurrence_series
                        WHERE id = ? AND plan_id = ? AND workspace_id = ?
                        """,
                        (rows, row) -> new Source(
                                rows.getObject("source_created_by_user_id", UUID.class),
                                rows.getInt("active_revision")),
                        seriesId,
                        planId,
                        context.workspaceId())
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new NotFoundException("Calendar recurrence series", seriesId));
        if (source.activeRevision() != recurrenceRuleRevision) {
            throw new BusinessException(
                    "STALE_RECURRENCE_REVISION",
                    "Calendar recurrence changed before reminders were materialized");
        }
        for (var item : planned) {
            var key = item.key();
            if (!seriesId.equals(key.seriesId())
                    || recurrenceRuleRevision != key.recurrenceRuleRevision()) {
                throw new IllegalArgumentException(
                        "Every reminder materialization must match the requested series revision");
            }
        }
        List<UUID> templateIds = planned.stream()
                .map(value -> value.key().templateId())
                .distinct()
                .toList();
        for (UUID templateId : templateIds) {
            Long owned = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM calendar_reminder_rule
                    WHERE id = ? AND plan_id = ?
                      AND workspace_id = ? AND created_by_user_id = ?
                    """,
                    Long.class,
                    templateId,
                    planId,
                    context.workspaceId(),
                    context.actorId());
            if (owned == null || owned != 1) {
                throw new NotFoundException("Calendar reminder template", templateId);
            }
        }

        int inserted = 0;
        Instant now = clock.instant();
        for (var item : planned) {
            var key = item.key();
            inserted += jdbc.update(
                    """
                    INSERT INTO calendar_recurrence_reminder_materialization (
                        id, reminder_rule_id, plan_id, series_id,
                        recurrence_rule_revision, logical_timed_start,
                        logical_all_day_start, reminder_rule_revision,
                        scheduled_at, materialization_status,
                        created_at, updated_at, workspace_id,
                        created_by_user_id, source_created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING',
                        ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    UUID.randomUUID(),
                    key.templateId(),
                    planId,
                    seriesId,
                    recurrenceRuleRevision,
                    key.occurrenceKey().timed()
                            ? Timestamp.valueOf(key.occurrenceKey().timedStart())
                            : null,
                    key.occurrenceKey().timed()
                            ? null
                            : java.sql.Date.valueOf(key.occurrenceKey().allDayStart()),
                    key.templateRevision(),
                    Timestamp.from(item.scheduledAt()),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    context.workspaceId(),
                    context.actorId(),
                    source.sourceOwnerId());
        }
        for (UUID templateId : templateIds) {
            jdbc.update(
                    """
                    INSERT INTO calendar_recurrence_reminder_cursor (
                        reminder_rule_id, series_id, recurrence_rule_revision,
                        horizon_exclusive, cursor_revision, updated_at,
                        workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)
                    ON CONFLICT (
                        reminder_rule_id, series_id, recurrence_rule_revision,
                        workspace_id, created_by_user_id)
                    DO UPDATE SET
                        horizon_exclusive = GREATEST(
                            calendar_recurrence_reminder_cursor.horizon_exclusive,
                            EXCLUDED.horizon_exclusive),
                        cursor_revision =
                            calendar_recurrence_reminder_cursor.cursor_revision + 1,
                        updated_at = EXCLUDED.updated_at
                    """,
                    templateId,
                    seriesId,
                    recurrenceRuleRevision,
                    Timestamp.from(horizonExclusive),
                    Timestamp.from(now),
                    context.workspaceId(),
                    context.actorId(),
                    source.sourceOwnerId());
        }
        return new ReminderMaterializationResult(inserted, planned.size(), horizonExclusive);
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

    public record ReminderMaterializationResult(
            int inserted, int planned, Instant horizonExclusive) {}
}
