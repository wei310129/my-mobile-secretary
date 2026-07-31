package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Registers the first owner recurrence revision for a newly created Calendar V2 plan. */
@Service
public class CalendarRecurrenceRegistrationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final JdbcTemplate jdbc;

    public CalendarRecurrenceRegistrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID registerPlan(
            UUID planId,
            CalendarPlacement placement,
            String recurrence,
            LocalDate recurrenceUntil,
            String requestKey) {
        if (planId == null || placement == null) {
            throw new IllegalArgumentException("plan and placement are required");
        }
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        TimedRule rule = timedRule(placement, recurrence, recurrenceUntil);
        UUID seriesId = UUID.nameUUIDFromBytes(
                ("calendar-recurrence:" + planId).getBytes(StandardCharsets.UTF_8));
        String requestHash = hash(requestKey);
        String payloadHash = hash(rule.toString());

        jdbc.update(
                """
                INSERT INTO calendar_recurrence_series (
                    id, plan_id, activity_id, lineage_root_id, active_revision,
                    created_at, updated_at, workspace_id, source_created_by_user_id)
                VALUES (?, ?, NULL, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                seriesId,
                planId,
                seriesId,
                context.workspaceId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO calendar_recurrence_rule_revision (
                    series_id, revision, frequency, recurrence_interval,
                    weekdays, week_start, month_day, weekday_ordinal, weekday,
                    timed_anchor, duration_seconds, zone_id,
                    end_kind, until_timed, effective_from_timed,
                    state, request_hash, payload_hash, created_by_actor_id,
                    created_at, workspace_id, source_created_by_user_id)
                VALUES (?, 1, ?, 1, CAST(? AS SMALLINT[]), ?, NULL, ?, ?,
                    ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?,
                    CURRENT_TIMESTAMP, ?, ?)
                ON CONFLICT (series_id, revision) DO NOTHING
                """,
                seriesId,
                rule.frequency(),
                rule.weekdays(),
                rule.weekStart(),
                rule.weekdayOrdinal(),
                rule.weekday(),
                Timestamp.valueOf(rule.anchor()),
                rule.duration().getSeconds(),
                TAIPEI.getId(),
                rule.endKind(),
                rule.until() == null ? null : Timestamp.valueOf(rule.until()),
                Timestamp.valueOf(rule.anchor()),
                requestHash,
                payloadHash,
                context.actorId(),
                context.workspaceId(),
                context.actorId());

        List<Registration> registrations = jdbc.query(
                """
                SELECT revision.request_hash, revision.payload_hash
                FROM calendar_recurrence_series series
                JOIN calendar_recurrence_rule_revision revision
                  ON revision.series_id = series.id
                 AND revision.revision = series.active_revision
                WHERE series.id = ? AND series.plan_id = ?
                  AND series.workspace_id = ?
                  AND series.source_created_by_user_id = ?
                """,
                (row, ignored) -> new Registration(
                        row.getString("request_hash"), row.getString("payload_hash")),
                seriesId,
                planId,
                context.workspaceId(),
                context.actorId());
        Registration persisted = registrations.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Calendar recurrence registration did not produce a revision"));
        if (!requestHash.equals(persisted.requestHash())
                || !payloadHash.equals(persisted.payloadHash())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The recurrence request was already used for different semantics");
        }
        return seriesId;
    }

    private static TimedRule timedRule(
            CalendarPlacement placement, String recurrence, LocalDate until) {
        LocalDateTime anchor;
        Duration duration;
        if (placement instanceof CalendarPlacement.TimedInterval interval) {
            anchor = interval.start().atZone(interval.zoneId()).toLocalDateTime();
            duration = Duration.between(interval.start(), interval.end());
        } else if (placement instanceof CalendarPlacement.TimedPoint point) {
            anchor = point.time().atZone(point.zoneId()).toLocalDateTime();
            duration = Duration.ZERO;
        } else {
            throw new IllegalArgumentException(
                    "Intent recurrence currently requires a timed Calendar placement");
        }
        String normalized = recurrence == null
                ? "WEEKLY"
                : recurrence.strip().toUpperCase(Locale.ROOT);
        String frequency;
        String weekdays = "{}";
        Integer weekStart = null;
        Integer ordinal = null;
        Integer weekday = null;
        switch (normalized) {
            case "DAILY" -> frequency = "DAILY";
            case "WEEKLY" -> {
                frequency = "WEEKLY";
                weekdays = "{" + anchor.getDayOfWeek().getValue() + "}";
                weekStart = 1;
            }
            case "WEEKDAYS" -> {
                frequency = "WEEKLY";
                weekdays = "{1,2,3,4,5}";
                weekStart = 1;
            }
            case "MONTHLY_NTH_WEEKDAY" -> {
                frequency = "MONTHLY";
                ordinal = ((anchor.getDayOfMonth() - 1) / 7) + 1;
                weekday = anchor.getDayOfWeek().getValue();
            }
            default -> throw new IllegalArgumentException(
                    "unsupported Calendar recurrence: " + normalized);
        }
        LocalDateTime untilTimed =
                until == null ? null : until.atTime(anchor.toLocalTime());
        return new TimedRule(
                frequency,
                weekdays,
                weekStart,
                ordinal,
                weekday,
                anchor,
                duration,
                untilTimed == null ? "UNBOUNDED" : "UNTIL_TIMED",
                untilTimed);
    }

    private static String hash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("recurrence request key is required");
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Registration(String requestHash, String payloadHash) {}

    private record TimedRule(
            String frequency,
            String weekdays,
            Integer weekStart,
            Integer weekdayOrdinal,
            Integer weekday,
            LocalDateTime anchor,
            Duration duration,
            String endKind,
            LocalDateTime until) {}
}
