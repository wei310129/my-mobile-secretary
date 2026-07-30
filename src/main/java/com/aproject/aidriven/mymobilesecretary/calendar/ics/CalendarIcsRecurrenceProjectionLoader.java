package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarOccurrenceKey;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceEnd;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceExceptions;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceExpander;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrencePattern;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceRule;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceWindow;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CalendarIcsRecurrenceProjectionLoader {

    public static final int MAX_EVENTS = 10_000;

    private final JdbcTemplate jdbc;

    public CalendarIcsRecurrenceProjectionLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CalendarIcsRecurrenceProjection load(
            UUID planId,
            LocalDate windowStart,
            LocalDate windowEndExclusive,
            WorkspaceContext context) {
        List<RuleRow> rules = ownerRules(planId, context);
        boolean adopted = rules.isEmpty();
        if (adopted) {
            rules = adoptedRules(planId, context);
        }
        List<CalendarIcsEvent> events = new ArrayList<>();
        Set<UUID> owners = new HashSet<>();
        for (RuleRow row : rules) {
            LocalDate start = max(windowStart, row.effectiveFrom());
            LocalDate end = min(windowEndExclusive, row.effectiveUntilExclusive());
            if (!end.isAfter(start)) {
                continue;
            }
            CalendarRecurrenceRule rule = rule(row);
            CalendarRecurrenceExceptions exceptions =
                    exceptions(row, adopted, context);
            int remaining = MAX_EVENTS - events.size();
            if (remaining <= 0) {
                throw tooMany();
            }
            var occurrences = CalendarRecurrenceExpander.expand(
                    rule,
                    exceptions,
                    CalendarRecurrenceWindow.between(start, end, remaining));
            owners.add(row.ownerId());
            for (var occurrence : occurrences) {
                events.add(event(row, occurrence.key(), occurrence.placement()));
            }
        }
        if (events.size() >= MAX_EVENTS) {
            throw tooMany();
        }
        return new CalendarIcsRecurrenceProjection(
                List.copyOf(events), Set.copyOf(owners));
    }

    private List<RuleRow> ownerRules(
            UUID planId, WorkspaceContext context) {
        return jdbc.query(
                """
                SELECT series.id AS series_id, series.plan_id,
                       series.activity_id,
                       COALESCE(activity.title, plan.title) AS projection_title,
                       revision.*
                FROM calendar_recurrence_series series
                JOIN calendar_recurrence_rule_revision revision
                  ON revision.series_id = series.id
                 AND revision.workspace_id = series.workspace_id
                 AND revision.source_created_by_user_id =
                        series.source_created_by_user_id
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
                WHERE series.plan_id = ? AND series.workspace_id = ?
                  AND revision.state <> 'CANCELED'
                ORDER BY series.id, revision.revision
                """,
                CalendarIcsRecurrenceProjectionLoader::ownerRule,
                planId,
                context.workspaceId());
    }

    private List<RuleRow> adoptedRules(
            UUID planId, WorkspaceContext context) {
        return jdbc.query(
                """
                SELECT snapshot.series_id, snapshot.plan_id,
                       snapshot.activity_id, snapshot.projection_title,
                       snapshot.rule_revision AS revision,
                       snapshot.frequency, snapshot.recurrence_interval,
                       snapshot.weekdays, snapshot.week_start,
                       snapshot.month_day, snapshot.weekday_ordinal,
                       snapshot.weekday, snapshot.year_month,
                       snapshot.year_day, snapshot.timed_anchor,
                       snapshot.duration_seconds, snapshot.zone_id,
                       snapshot.all_day_anchor, snapshot.all_day_span,
                       snapshot.end_kind, snapshot.occurrence_count,
                       snapshot.until_date, snapshot.until_timed,
                       snapshot.effective_from_timed,
                       snapshot.effective_from_date,
                       snapshot.effective_until_timed,
                       snapshot.effective_until_date
                FROM calendar_recurrence_adoption_rule_snapshot snapshot
                JOIN calendar_recurrence_adoption adoption
                  ON adoption.id = snapshot.recurrence_adoption_id
                 AND adoption.workspace_id = snapshot.workspace_id
                 AND adoption.created_by_user_id =
                        snapshot.created_by_user_id
                WHERE snapshot.plan_id = ? AND snapshot.workspace_id = ?
                  AND snapshot.created_by_user_id = ?
                  AND adoption.adoption_status = 'ACTIVE'
                ORDER BY snapshot.series_id, snapshot.rule_revision
                """,
                (row, ignored) -> ruleRow(row, true),
                planId,
                context.workspaceId(),
                context.actorId());
    }

    private CalendarRecurrenceExceptions exceptions(
            RuleRow rule, boolean adopted, WorkspaceContext context) {
        String table = adopted
                ? "calendar_recurrence_adoption_exception_snapshot"
                : "calendar_recurrence_exception";
        String revisionColumn = adopted ? "rule_revision" : "rule_revision";
        List<ExceptionRow> rows = jdbc.query(
                """
                SELECT logical_timed_start, logical_all_day_start, kind,
                       placement_kind, timed_start, timed_end, zone_id,
                       all_day_start, all_day_end_exclusive
                FROM %s
                WHERE series_id = ? AND %s = ?
                  AND workspace_id = ?
                """.formatted(table, revisionColumn),
                CalendarIcsRecurrenceProjectionLoader::exceptionRow,
                rule.seriesId(),
                rule.revision(),
                context.workspaceId());
        CalendarRecurrenceExceptions result =
                CalendarRecurrenceExceptions.none();
        for (ExceptionRow row : rows) {
            CalendarOccurrenceKey key = row.timedKey() == null
                    ? CalendarOccurrenceKey.allDay(row.allDayKey())
                    : CalendarOccurrenceKey.timed(row.timedKey());
            result = switch (row.kind()) {
                case "EXCLUDED" -> result.exclude(key);
                case "ADDED" -> result.add(key, row.placement());
                case "OVERRIDDEN" -> result.override(key, row.placement());
                default -> throw malformed();
            };
        }
        return result;
    }

    private static CalendarRecurrenceRule rule(RuleRow row) {
        CalendarRecurrencePattern pattern =
                CalendarIcsRecurrenceProjectionMapper.pattern(
                        row.frequency(),
                        row.weekdays(),
                        row.weekStart(),
                        row.monthDay(),
                        row.weekdayOrdinal(),
                        row.weekday(),
                        row.yearMonth(),
                        row.yearDay());
        CalendarRecurrenceEnd end = CalendarIcsRecurrenceProjectionMapper.end(
                row.endKind(),
                row.occurrenceCount(),
                row.untilDate(),
                row.untilTimed());
        return row.timedAnchor() == null
                ? CalendarRecurrenceRule.allDay(
                        row.allDayAnchor(),
                        row.allDaySpan(),
                        pattern,
                        row.interval(),
                        end)
                : CalendarRecurrenceRule.timed(
                        row.timedAnchor(),
                        Duration.ofSeconds(row.durationSeconds()),
                        ZoneId.of(row.zoneId()),
                        pattern,
                        row.interval(),
                        end);
    }

    private static CalendarIcsEvent event(
            RuleRow row, CalendarOccurrenceKey key, CalendarPlacement placement) {
        UUID id = UUID.nameUUIDFromBytes(
                (row.seriesId() + "|" + row.revision() + "|" + key)
                        .getBytes(StandardCharsets.UTF_8));
        return switch (placement) {
            case CalendarPlacement.TimedInterval timed ->
                new CalendarIcsEvent(
                        id,
                        "recurrence",
                        row.title(),
                        "",
                        timed.start(),
                        timed.end(),
                        timed.zoneId(),
                        null,
                        null);
            case CalendarPlacement.TimedPoint timed ->
                new CalendarIcsEvent(
                        id,
                        "recurrence",
                        row.title(),
                        "",
                        timed.time(),
                        timed.time().plusSeconds(60),
                        timed.zoneId(),
                        null,
                        null);
            case CalendarPlacement.AllDay allDay ->
                CalendarIcsEvent.allDay(
                        id,
                        "recurrence",
                        row.title(),
                        "",
                        allDay.start(),
                        allDay.endExclusive());
        };
    }

    private static RuleRow ownerRule(ResultSet row, int ignored)
            throws SQLException {
        return ruleRow(row, false);
    }

    private static RuleRow ruleRow(ResultSet row, boolean snapshot)
            throws SQLException {
        LocalDate from = date(
                row,
                "effective_from_timed",
                "effective_from_date");
        LocalDate until = nullableDate(
                row,
                "effective_until_timed",
                "effective_until_date");
        return new RuleRow(
                row.getObject("series_id", UUID.class),
                row.getObject("activity_id", UUID.class) == null
                        ? row.getObject("plan_id", UUID.class)
                        : row.getObject("activity_id", UUID.class),
                row.getString("projection_title"),
                row.getInt("revision"),
                row.getString("frequency"),
                row.getInt("recurrence_interval"),
                integers(row.getArray("weekdays")),
                nullableInteger(row, "week_start"),
                nullableInteger(row, "month_day"),
                nullableInteger(row, "weekday_ordinal"),
                nullableInteger(row, "weekday"),
                nullableInteger(row, "year_month"),
                nullableInteger(row, "year_day"),
                localDateTime(row, "timed_anchor"),
                nullableLong(row, "duration_seconds"),
                row.getString("zone_id"),
                row.getObject("all_day_anchor", LocalDate.class),
                nullableInteger(row, "all_day_span"),
                row.getString("end_kind"),
                nullableInteger(row, "occurrence_count"),
                row.getObject("until_date", LocalDate.class),
                localDateTime(row, "until_timed"),
                from,
                until == null ? LocalDate.MAX : until);
    }

    private static ExceptionRow exceptionRow(ResultSet row, int ignored)
            throws SQLException {
        String kind = row.getString("kind");
        String placementKind = row.getString("placement_kind");
        CalendarPlacement placement = null;
        if (!"EXCLUDED".equals(kind)) {
            placement = switch (placementKind) {
                case "TIMED_INTERVAL" -> CalendarPlacement.interval(
                        row.getTimestamp("timed_start").toInstant(),
                        row.getTimestamp("timed_end").toInstant(),
                        ZoneId.of(row.getString("zone_id")));
                case "TIMED_POINT" -> CalendarPlacement.point(
                        row.getTimestamp("timed_start").toInstant(),
                        ZoneId.of(row.getString("zone_id")));
                case "ALL_DAY" -> CalendarPlacement.allDay(
                        row.getObject("all_day_start", LocalDate.class),
                        row.getObject(
                                "all_day_end_exclusive",
                                LocalDate.class));
                default -> throw malformed();
            };
        }
        return new ExceptionRow(
                localDateTime(row, "logical_timed_start"),
                row.getObject("logical_all_day_start", LocalDate.class),
                kind,
                placement);
    }

    private static List<Integer> integers(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        List<Integer> result = new ArrayList<>(values.length);
        for (Object value : values) {
            result.add(((Number) value).intValue());
        }
        return List.copyOf(result);
    }

    private static LocalDate date(
            ResultSet row, String timedColumn, String dateColumn)
            throws SQLException {
        LocalDate value = nullableDate(row, timedColumn, dateColumn);
        if (value == null) {
            throw malformed();
        }
        return value;
    }

    private static LocalDate nullableDate(
            ResultSet row, String timedColumn, String dateColumn)
            throws SQLException {
        LocalDateTime timed = localDateTime(row, timedColumn);
        LocalDate date = row.getObject(dateColumn, LocalDate.class);
        if ((timed == null) == (date == null)) {
            if (timed == null) {
                return null;
            }
            throw malformed();
        }
        return timed == null ? date : timed.toLocalDate();
    }

    private static LocalDateTime localDateTime(
            ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Integer nullableInteger(
            ResultSet row, String column) throws SQLException {
        Number value = (Number) row.getObject(column);
        return value == null ? null : value.intValue();
    }

    private static Long nullableLong(ResultSet row, String column)
            throws SQLException {
        Number value = (Number) row.getObject(column);
        return value == null ? null : value.longValue();
    }

    private static LocalDate max(LocalDate left, LocalDate right) {
        return left.isAfter(right) ? left : right;
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(
                "Malformed recurrence persistence projection");
    }

    private static IllegalArgumentException tooMany() {
        return new IllegalArgumentException(
                "ICS recurrence export exceeds 10000 events");
    }

    private record RuleRow(
            UUID seriesId,
            UUID ownerId,
            String title,
            int revision,
            String frequency,
            int interval,
            List<Integer> weekdays,
            Integer weekStart,
            Integer monthDay,
            Integer weekdayOrdinal,
            Integer weekday,
            Integer yearMonth,
            Integer yearDay,
            LocalDateTime timedAnchor,
            Long durationSeconds,
            String zoneId,
            LocalDate allDayAnchor,
            Integer allDaySpan,
            String endKind,
            Integer occurrenceCount,
            LocalDate untilDate,
            LocalDateTime untilTimed,
            LocalDate effectiveFrom,
            LocalDate effectiveUntilExclusive) {}

    private record ExceptionRow(
            LocalDateTime timedKey,
            LocalDate allDayKey,
            String kind,
            CalendarPlacement placement) {}
}
