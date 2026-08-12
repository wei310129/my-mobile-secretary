package com.aproject.aidriven.mymobilesecretary.calendar.query;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CalendarQueryService {

    private static final String QUERY =
            """
            SELECT p.title, p.placement_kind, p.timed_start, p.timed_end, p.zone_id,
                   p.all_day_start, p.all_day_end_exclusive, p.category, l.safe_host
            FROM calendar_plan p
            LEFT JOIN calendar_online_access_link l
              ON l.plan_id = p.id
             AND l.activity_id IS NULL
             AND l.node_id IS NULL
             AND l.workspace_id = p.workspace_id
             AND l.created_by_user_id = p.created_by_user_id
            WHERE p.workspace_id = :workspaceId
              AND p.status = 'ACTIVE'
              AND (
                    p.created_by_user_id = :actorId
                    OR EXISTS (
                        SELECT 1
                        FROM calendar_share share
                        WHERE share.plan_id = p.id
                          AND share.workspace_id = p.workspace_id
                          AND share.created_by_user_id =
                              p.created_by_user_id
                          AND share.grantee_user_id = :actorId
                          AND share.scope_mode IN (
                              'LIVE_WHOLE_PLAN',
                              'SELECTED_ACTIVITIES',
                              'SELECTED_NODES')
                          AND share.permission = 'VIEWER'
                          AND share.status = 'ACTIVE'
                    )
              )
              AND (
                    :hasRange = false
                    OR (
                        p.placement_kind = 'TIMED_INTERVAL'
                        AND p.timed_start < :toInstant
                        AND p.timed_end > :fromInstant
                    )
                    OR (
                        p.placement_kind = 'TIMED_POINT'
                        AND p.timed_start >= :fromInstant
                        AND p.timed_start < :toInstant
                    )
                    OR (
                        p.placement_kind = 'ALL_DAY'
                        AND p.all_day_start < :toDate
                        AND p.all_day_end_exclusive > :fromDate
                    )
              )
              AND (:hasKeyword = false OR lower(p.title) LIKE :keywordPattern ESCAPE '\\')
              AND (:hasCategory = false OR lower(p.category) = :category)
              AND (:hasExcludedPlan = false OR p.id <> :excludedPlanId)
            ORDER BY
              CASE
                WHEN :hasKeyword = false THEN 0
                WHEN lower(p.title) = :keyword THEN 0
                WHEN lower(p.title) LIKE :keywordPrefix ESCAPE '\\' THEN 1
                ELSE 2
              END,
              CASE WHEN p.placement_kind = 'ALL_DAY' THEN 0 ELSE 1 END,
              COALESCE(p.all_day_start, CAST(p.timed_start AT TIME ZONE :zoneId AS date)),
              p.timed_start NULLS FIRST,
              lower(p.title),
              p.id
            LIMIT :fetchLimit OFFSET :offset
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CalendarQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CalendarQueryPage query(CalendarQueryFilter filter) {
        return query(filter, null);
    }

    public CalendarQueryPage queryExcludingPlan(
            CalendarQueryFilter filter, java.util.UUID excludedPlanId) {
        if (excludedPlanId == null) return query(filter);
        return query(filter, excludedPlanId);
    }

    private CalendarQueryPage query(
            CalendarQueryFilter filter, java.util.UUID excludedPlanId) {
        WorkspaceContext context = tenantContext();
        Map<String, Object> parameters = parameters(filter, context, excludedPlanId);
        List<CalendarQueryItem> rows =
                jdbc.query(QUERY, parameters, CalendarQueryService::mapItem);
        boolean hasMore = rows.size() > filter.limit();
        List<CalendarQueryItem> items =
                hasMore ? List.copyOf(rows.subList(0, filter.limit())) : List.copyOf(rows);
        return new CalendarQueryPage(
                items, hasMore ? filter.offset() + filter.limit() : null);
    }

    private static Map<String, Object> parameters(
            CalendarQueryFilter filter,
            WorkspaceContext context,
            java.util.UUID excludedPlanId) {
        Map<String, Object> values = new HashMap<>();
        boolean hasRange = filter.fromInclusive() != null;
        boolean hasKeyword = filter.keyword() != null;
        boolean hasCategory = filter.category() != null;
        Instant from = hasRange ? filter.fromInclusive() : Instant.EPOCH;
        Instant to = hasRange ? filter.toExclusive() : Instant.parse("9999-12-31T23:59:59Z");
        LocalDate fromDate = from.atZone(filter.zoneId()).toLocalDate();
        var zonedTo = to.atZone(filter.zoneId());
        LocalDate toDate = zonedTo.toLocalDate();
        if (!zonedTo.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            toDate = toDate.plusDays(1);
        }
        String keyword = hasKeyword
                ? filter.keyword().toLowerCase(Locale.ROOT)
                : "";
        String escapedKeyword = escapeLike(keyword);
        values.put("workspaceId", context.workspaceId());
        values.put("actorId", context.actorId());
        values.put("hasRange", hasRange);
        values.put("fromInstant", Timestamp.from(from));
        values.put("toInstant", Timestamp.from(to));
        values.put("fromDate", Date.valueOf(fromDate));
        values.put("toDate", Date.valueOf(toDate));
        values.put("hasKeyword", hasKeyword);
        values.put("keyword", keyword);
        values.put("keywordPattern", "%" + escapedKeyword + "%");
        values.put("keywordPrefix", escapedKeyword + "%");
        values.put("hasCategory", hasCategory);
        values.put(
                "category",
                hasCategory ? filter.category().toLowerCase(Locale.ROOT) : "");
        values.put("zoneId", filter.zoneId().getId());
        values.put("fetchLimit", filter.limit() + 1);
        values.put("offset", filter.offset());
        values.put("hasExcludedPlan", excludedPlanId != null);
        values.put(
                "excludedPlanId",
                excludedPlanId == null
                        ? new java.util.UUID(0L, 0L)
                        : excludedPlanId);
        return values;
    }

    private static CalendarQueryItem mapItem(ResultSet result, int rowNumber)
            throws SQLException {
        String kind = result.getString("placement_kind");
        CalendarPlacement placement;
        if (CalendarPlacement.Kind.TIMED_INTERVAL.name().equals(kind)) {
            placement = CalendarPlacement.interval(
                    result.getTimestamp("timed_start").toInstant(),
                    result.getTimestamp("timed_end").toInstant(),
                    ZoneId.of(result.getString("zone_id")));
        } else if (CalendarPlacement.Kind.TIMED_POINT.name().equals(kind)) {
            placement = CalendarPlacement.point(
                    result.getTimestamp("timed_start").toInstant(),
                    ZoneId.of(result.getString("zone_id")));
        } else {
            placement = CalendarPlacement.allDay(
                    result.getObject("all_day_start", LocalDate.class),
                    result.getObject("all_day_end_exclusive", LocalDate.class));
        }
        return new CalendarQueryItem(
                result.getString("title"),
                placement,
                result.getString("category"),
                result.getString("safe_host"));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar queries require a tenant workspace");
        }
        return context;
    }
}
