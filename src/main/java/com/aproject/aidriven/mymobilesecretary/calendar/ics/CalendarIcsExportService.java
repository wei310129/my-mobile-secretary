package com.aproject.aidriven.mymobilesecretary.calendar.ics;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarIcsExportService {

    private static final String LOSS_REPORT =
            "ACK, retry, escalation, shared reminder templates, and adoption review are not preserved";

    private final JdbcTemplate jdbc;
    private final CalendarIcsExportArtifactService artifacts;

    public CalendarIcsExportService(
            JdbcTemplate jdbc, CalendarIcsExportArtifactService artifacts) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
    }

    public CalendarIcsExportArtifact export(CalendarIcsExportCommand command) {
        if (command == null
                || command.planId() == null
                || command.profile() == null) {
            throw new IllegalArgumentException("A calendar export command is required");
        }
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        List<CalendarIcsEvent> events = new ArrayList<>(jdbc.query(
                """
                SELECT plan.id, 'plan' AS source_type, plan.title,
                       plan.placement_kind, plan.timed_start, plan.timed_end,
                       plan.zone_id, plan.all_day_start,
                       plan.all_day_end_exclusive,
                       COALESCE(string_agg(node.label, '; ' ORDER BY node.created_at), '')
                            AS node_summary
                FROM calendar_plan plan
                LEFT JOIN calendar_time_node node
                  ON node.plan_id = plan.id
                 AND node.workspace_id = plan.workspace_id
                 AND node.created_by_user_id = plan.created_by_user_id
                LEFT JOIN calendar_plan_ownership ownership
                  ON ownership.plan_id = plan.id
                 AND ownership.workspace_id = plan.workspace_id
                 AND ownership.source_created_by_user_id =
                        plan.created_by_user_id
                WHERE plan.id = ? AND plan.workspace_id = ?
                  AND plan.status = 'ACTIVE'
                  AND COALESCE(ownership.owner_user_id,
                               plan.created_by_user_id) = ?
                GROUP BY plan.id, plan.title, plan.placement_kind,
                         plan.timed_start, plan.timed_end, plan.zone_id,
                         plan.all_day_start, plan.all_day_end_exclusive
                """,
                CalendarIcsExportService::event,
                command.planId(),
                context.workspaceId(),
                context.actorId()));
        if (!events.isEmpty()) {
            events.addAll(jdbc.query(
                    """
                    SELECT activity.id, 'activity' AS source_type,
                           activity.title, activity.placement_kind,
                           activity.timed_start, activity.timed_end,
                           activity.zone_id, activity.all_day_start,
                           activity.all_day_end_exclusive,
                           COALESCE(string_agg(node.label, '; '
                               ORDER BY node.created_at), '') AS node_summary
                    FROM calendar_activity activity
                    LEFT JOIN calendar_time_node node
                      ON node.activity_id = activity.id
                     AND node.workspace_id = activity.workspace_id
                     AND node.created_by_user_id =
                            activity.created_by_user_id
                    WHERE activity.plan_id = ?
                      AND activity.workspace_id = ?
                    GROUP BY activity.id, activity.title,
                             activity.placement_kind,
                             activity.timed_start, activity.timed_end,
                             activity.zone_id, activity.all_day_start,
                             activity.all_day_end_exclusive
                    """,
                    CalendarIcsExportService::event,
                    command.planId(),
                    context.workspaceId()));
        } else {
            events.addAll(adoptedEvents(command, context));
        }
        events.removeIf(event -> !withinWindow(event, command));
        if (events.isEmpty()) {
            throw new SecurityException(
                    "Calendar projection is unavailable for export");
        }
        byte[] content = CalendarIcsWriter.write(events);
        return artifacts.create(new CalendarIcsExportRequest(
                command.requestId(),
                command.planId(),
                command.windowStart(),
                command.windowEndExclusive(),
                command.profile(),
                LOSS_REPORT,
                content));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private List<CalendarIcsEvent> adoptedEvents(
            CalendarIcsExportCommand command, WorkspaceContext context) {
        List<CalendarIcsEvent> result = new ArrayList<>(jdbc.query(
                """
                SELECT snapshot.plan_id AS id, 'plan' AS source_type,
                       snapshot.plan_title AS title,
                       snapshot.placement_kind, snapshot.timed_start,
                       snapshot.timed_end, snapshot.zone_id,
                       snapshot.all_day_start,
                       snapshot.all_day_end_exclusive,
                       COALESCE(string_agg(node.label, '; '
                           ORDER BY node.created_at), '') AS node_summary
                FROM calendar_personal_projection_snapshot snapshot
                JOIN calendar_adoption adoption
                  ON adoption.id = snapshot.adoption_id
                 AND adoption.workspace_id = snapshot.workspace_id
                 AND adoption.created_by_user_id =
                        snapshot.created_by_user_id
                LEFT JOIN calendar_personal_projection_node node
                  ON node.snapshot_id = snapshot.id
                 AND node.workspace_id = snapshot.workspace_id
                 AND node.created_by_user_id =
                        snapshot.created_by_user_id
                 AND node.cancellation_status = 'ACTIVE'
                WHERE snapshot.plan_id = ? AND snapshot.workspace_id = ?
                  AND snapshot.created_by_user_id = ?
                  AND snapshot.projection_status IN (
                    'ACTIVE', 'RETAINED_NO_SOURCE_ACCESS')
                  AND adoption.status = 'ACTIVE'
                  AND snapshot.projection_revision = (
                    SELECT max(newer.projection_revision)
                    FROM calendar_personal_projection_snapshot newer
                    WHERE newer.adoption_id = snapshot.adoption_id
                      AND newer.workspace_id = snapshot.workspace_id
                      AND newer.created_by_user_id =
                            snapshot.created_by_user_id)
                GROUP BY snapshot.plan_id, snapshot.plan_title,
                         snapshot.placement_kind, snapshot.timed_start,
                         snapshot.timed_end, snapshot.zone_id,
                         snapshot.all_day_start,
                         snapshot.all_day_end_exclusive
                """,
                CalendarIcsExportService::event,
                command.planId(),
                context.workspaceId(),
                context.actorId()));
        if (command.profile() == CalendarIcsExportProfile.ROUTE_AWARE) {
            result.addAll(jdbc.query(
                    """
                    SELECT node.source_node_id AS id,
                           'route-node' AS source_type,
                           node.label AS title, 'TIMED_INTERVAL' AS placement_kind,
                           node.resolved_time AS timed_start,
                           node.resolved_time + interval '1 minute' AS timed_end,
                           snapshot.zone_id, NULL::date AS all_day_start,
                           NULL::date AS all_day_end_exclusive,
                           '' AS node_summary
                    FROM calendar_personal_projection_snapshot snapshot
                    JOIN calendar_personal_projection_node node
                      ON node.snapshot_id = snapshot.id
                     AND node.workspace_id = snapshot.workspace_id
                     AND node.created_by_user_id =
                            snapshot.created_by_user_id
                    WHERE snapshot.plan_id = ? AND snapshot.workspace_id = ?
                      AND snapshot.created_by_user_id = ?
                      AND snapshot.projection_status IN (
                        'ACTIVE', 'RETAINED_NO_SOURCE_ACCESS')
                      AND node.cancellation_status = 'ACTIVE'
                    """,
                    CalendarIcsExportService::event,
                    command.planId(),
                    context.workspaceId(),
                    context.actorId()));
        }
        return result;
    }

    private static CalendarIcsEvent event(ResultSet row, int ignored)
            throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        String type = row.getString("source_type");
        if ("ALL_DAY".equals(row.getString("placement_kind"))) {
            return CalendarIcsEvent.allDay(
                    id,
                    type,
                    row.getString("title"),
                    row.getString("node_summary"),
                    row.getObject("all_day_start", LocalDate.class),
                    row.getObject("all_day_end_exclusive", LocalDate.class));
        }
        Instant start = instant(row.getTimestamp("timed_start"));
        Instant end = instant(row.getTimestamp("timed_end"));
        if (end == null) {
            end = start.plusSeconds(60);
        }
        return new CalendarIcsEvent(
                id,
                type,
                row.getString("title"),
                row.getString("node_summary"),
                start,
                end,
                ZoneId.of(row.getString("zone_id")),
                null,
                null);
    }

    private static boolean withinWindow(
            CalendarIcsEvent event, CalendarIcsExportCommand command) {
        LocalDate start = event.timed()
                ? event.startsAt().atZone(event.zoneId()).toLocalDate()
                : event.allDayStart();
        LocalDate end = event.timed()
                ? event.endsAt().atZone(event.zoneId()).toLocalDate()
                : event.allDayEndExclusive().minusDays(1);
        return end.compareTo(command.windowStart()) >= 0
                && start.compareTo(command.windowEndExclusive()) < 0;
    }
}
