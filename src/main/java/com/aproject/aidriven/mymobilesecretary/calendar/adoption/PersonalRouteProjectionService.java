package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PersonalRouteProjectionService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final CalendarAdoptionService adoptions;
    private final JdbcTemplate jdbc;

    public PersonalRouteProjectionService(
            CalendarAdoptionService adoptions, JdbcTemplate jdbc) {
        this.adoptions = adoptions;
        this.jdbc = jdbc;
    }

    public PersonalRouteProjection current() {
        WorkspaceContext context = tenantContext();
        List<CalendarBusyInterval> busy = jdbc.query(
                """
                SELECT snapshot.plan_title, snapshot.placement_kind,
                       snapshot.timed_start, snapshot.timed_end,
                       snapshot.all_day_start,
                       snapshot.all_day_end_exclusive
                FROM calendar_personal_projection_snapshot snapshot
                JOIN calendar_adoption adoption
                  ON adoption.id = snapshot.adoption_id
                 AND adoption.workspace_id = snapshot.workspace_id
                 AND adoption.created_by_user_id =
                     snapshot.created_by_user_id
                LEFT JOIN calendar_adoption_source_lifecycle lifecycle
                  ON lifecycle.plan_id = snapshot.plan_id
                 AND lifecycle.workspace_id = snapshot.workspace_id
                 AND lifecycle.source_created_by_user_id =
                     snapshot.source_created_by_user_id
                WHERE adoption.status = 'ACTIVE'
                  AND snapshot.projection_status IN (
                      'ACTIVE', 'RETAINED_NO_SOURCE_ACCESS')
                  AND (
                      snapshot.projection_status =
                          'RETAINED_NO_SOURCE_ACCESS'
                      OR lifecycle.plan_id IS NULL)
                  AND snapshot.workspace_id = ?
                  AND snapshot.created_by_user_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM calendar_projection_suppression suppression
                      WHERE suppression.plan_id = snapshot.plan_id
                        AND suppression.target_scope = 'PLAN'
                        AND suppression.status = 'ACTIVE'
                        AND suppression.workspace_id =
                            snapshot.workspace_id
                        AND suppression.created_by_user_id =
                            snapshot.created_by_user_id)
                ORDER BY COALESCE(
                    snapshot.timed_start,
                    snapshot.all_day_start::timestamp
                        AT TIME ZONE 'Asia/Taipei'),
                    lower(snapshot.plan_title)
                """,
                (row, ignored) -> {
                    String kind = row.getString("placement_kind");
                    if ("TIMED_POINT".equals(kind)) {
                        return null;
                    }
                    Instant start;
                    Instant end;
                    if ("ALL_DAY".equals(kind)) {
                        LocalDate startDate =
                                row.getObject("all_day_start", LocalDate.class);
                        LocalDate endDate =
                                row.getObject("all_day_end_exclusive", LocalDate.class);
                        start = startDate.atStartOfDay(TAIPEI).toInstant();
                        end = endDate.atStartOfDay(TAIPEI).toInstant();
                    } else {
                        start = row.getTimestamp("timed_start").toInstant();
                        end = row.getTimestamp("timed_end").toInstant();
                    }
                    return new CalendarBusyInterval(
                            row.getString("plan_title"), start, end);
                },
                context.workspaceId(),
                context.actorId()).stream().filter(java.util.Objects::nonNull).toList();
        return new PersonalRouteProjection(busy, adoptions.constraints());
    }

    public List<CalendarBusyInterval> busyIntervals(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("Projection range must be ordered");
        }
        return current().busyIntervals().stream()
                .filter(interval -> interval.overlaps(from, to))
                .toList();
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Personal route projection requires tenant scope");
        }
        return context;
    }
}
