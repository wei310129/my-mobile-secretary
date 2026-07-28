package com.aproject.aidriven.mymobilesecretary.calendar.share;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CalendarSelectedScopeQueryService {

    private final JdbcTemplate jdbc;

    public CalendarSelectedScopeQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CalendarSelectedScopeView get(UUID planId) {
        WorkspaceContext context = CalendarShareService.context();
        List<UUID> shares = jdbc.queryForList(
                """
                SELECT id
                FROM calendar_share
                WHERE plan_id = ? AND workspace_id = ?
                  AND grantee_user_id = ? AND status = 'ACTIVE'
                  AND scope_mode IN (
                      'SELECTED_ACTIVITIES', 'SELECTED_NODES')
                ORDER BY id
                """,
                UUID.class,
                planId,
                context.workspaceId(),
                context.actorId());
        if (shares.isEmpty()) {
            throw new NotFoundException(
                    "Calendar selected scope", "requested plan");
        }
        PlanContext plan = jdbc.queryForObject(
                """
                SELECT calendar_plan.title, calendar_plan.status,
                       owner.display_name
                FROM calendar_plan
                JOIN app_user owner
                  ON owner.id = calendar_plan.created_by_user_id
                WHERE calendar_plan.id = ?
                  AND calendar_plan.workspace_id = ?
                """,
                (row, index) -> new PlanContext(
                        row.getString("title"),
                        row.getString("status"),
                        row.getString("display_name")),
                planId,
                context.workspaceId());
        List<CalendarSelectedScopeView.ActivityContext> selectedActivities =
                jdbc.query(
                """
                SELECT DISTINCT activity.id, activity.title,
                       link.safe_host
                FROM calendar_share share_row
                JOIN calendar_share_scope_item item
                  ON item.snapshot_id =
                      share_row.current_scope_snapshot_id
                 AND item.share_id = share_row.id
                JOIN calendar_activity activity
                  ON activity.id = item.activity_id
                 AND activity.plan_id = share_row.plan_id
                 AND activity.workspace_id = share_row.workspace_id
                 AND activity.created_by_user_id =
                     share_row.created_by_user_id
                LEFT JOIN calendar_online_access_link link
                  ON link.activity_id = activity.id
                 AND link.node_id IS NULL
                 AND link.workspace_id = activity.workspace_id
                 AND link.created_by_user_id =
                     activity.created_by_user_id
                WHERE share_row.plan_id = ?
                  AND share_row.workspace_id = ?
                  AND share_row.grantee_user_id = ?
                  AND share_row.status = 'ACTIVE'
                  AND item.target_kind = 'ACTIVITY'
                ORDER BY activity.id
                """,
                (row, index) -> new CalendarSelectedScopeView.ActivityContext(
                        row.getObject("id", UUID.class),
                        row.getString("title"),
                        true,
                        row.getString("safe_host")),
                planId,
                context.workspaceId(),
                context.actorId());
        List<CalendarSelectedScopeView.ActivityContext> activityContexts =
                jdbc.query(
                        """
                        SELECT DISTINCT item.activity_id, item.context_title
                        FROM calendar_share share_row
                        JOIN calendar_share_scope_item item
                          ON item.snapshot_id =
                              share_row.current_scope_snapshot_id
                         AND item.share_id = share_row.id
                        WHERE share_row.plan_id = ?
                          AND share_row.workspace_id = ?
                          AND share_row.grantee_user_id = ?
                          AND share_row.status = 'ACTIVE'
                          AND item.target_kind = 'ACTIVITY_CONTEXT'
                        ORDER BY item.activity_id
                        """,
                        (row, index) ->
                                new CalendarSelectedScopeView.ActivityContext(
                                        row.getObject(
                                                "activity_id", UUID.class),
                                        row.getString("context_title"),
                                        false,
                                        null),
                        planId,
                        context.workspaceId(),
                        context.actorId());
        Map<UUID, CalendarSelectedScopeView.ActivityContext> activityById =
                new HashMap<>();
        for (CalendarSelectedScopeView.ActivityContext activity :
                selectedActivities) {
            activityById.put(activity.id(), activity);
        }
        for (CalendarSelectedScopeView.ActivityContext activity :
                activityContexts) {
            activityById.putIfAbsent(activity.id(), activity);
        }
        List<CalendarSelectedScopeView.ActivityContext> activities =
                activityById.values().stream()
                        .sorted(Comparator.comparing(
                                activity -> activity.id().toString()))
                        .toList();
        List<CalendarSelectedScopeView.NodeTarget> nodes = jdbc.query(
                """
                SELECT DISTINCT node.id, node.activity_id, node.label,
                       node.resolved_time, node.expression_kind,
                       node.offset_seconds, node.location_label,
                       node.criticality, node.adjustability,
                       node.cancellation_status, node.canceled_at,
                       node.revision, link.safe_host
                FROM calendar_share share_row
                JOIN calendar_share_scope_item item
                  ON item.snapshot_id =
                      share_row.current_scope_snapshot_id
                 AND item.share_id = share_row.id
                JOIN calendar_time_node node
                  ON node.id = item.node_id
                 AND node.plan_id = share_row.plan_id
                 AND node.workspace_id = share_row.workspace_id
                 AND node.created_by_user_id =
                     share_row.created_by_user_id
                LEFT JOIN calendar_online_access_link link
                  ON link.node_id = node.id
                 AND link.activity_id IS NULL
                 AND link.workspace_id = node.workspace_id
                 AND link.created_by_user_id = node.created_by_user_id
                WHERE share_row.plan_id = ?
                  AND share_row.workspace_id = ?
                  AND share_row.grantee_user_id = ?
                  AND share_row.status = 'ACTIVE'
                  AND item.target_kind = 'NODE'
                  AND item.dependency_minimum = FALSE
                ORDER BY node.id
                """,
                (row, index) -> {
                    Timestamp resolved = row.getTimestamp("resolved_time");
                    return new CalendarSelectedScopeView.NodeTarget(
                            row.getObject("id", UUID.class),
                            row.getObject("activity_id", UUID.class),
                            row.getString("label"),
                            resolved == null ? null : resolved.toInstant(),
                            row.getString("expression_kind"),
                            row.getObject("offset_seconds", Long.class),
                            row.getString("location_label"),
                            row.getString("criticality"),
                            row.getString("adjustability"),
                            row.getString("cancellation_status"),
                            row.getTimestamp("canceled_at") == null
                                    ? null
                                    : row.getTimestamp("canceled_at")
                                            .toInstant(),
                            row.getLong("revision"),
                            row.getString("safe_host"));
                },
                planId,
                context.workspaceId(),
                context.actorId());
        List<CalendarSelectedScopeView.DependencyMinimum> dependencies =
                jdbc.query(
                        """
                        SELECT DISTINCT item.node_id,
                               item.dependent_node_id,
                               item.dependency_resolved_time,
                               item.dependency_revision
                        FROM calendar_share share_row
                        JOIN calendar_share_scope_item item
                          ON item.snapshot_id =
                              share_row.current_scope_snapshot_id
                         AND item.share_id = share_row.id
                        WHERE share_row.plan_id = ?
                          AND share_row.workspace_id = ?
                          AND share_row.grantee_user_id = ?
                          AND share_row.status = 'ACTIVE'
                          AND item.target_kind = 'DEPENDENCY_MINIMUM'
                          AND item.dependency_minimum = TRUE
                        ORDER BY item.node_id
                        """,
                        (row, index) ->
                                new CalendarSelectedScopeView.DependencyMinimum(
                                        row.getObject("node_id", UUID.class),
                                        row.getObject(
                                                "dependent_node_id",
                                                UUID.class),
                                        row.getTimestamp(
                                                        "dependency_resolved_time")
                                                .toInstant(),
                                        row.getLong("dependency_revision")),
                        planId,
                        context.workspaceId(),
                        context.actorId());
        return new CalendarSelectedScopeView(
                planId,
                plan.title(),
                plan.status(),
                plan.ownerDisplayName(),
                List.copyOf(shares),
                List.copyOf(activities),
                List.copyOf(nodes),
                List.copyOf(dependencies));
    }

    private record PlanContext(
            String title, String status, String ownerDisplayName) {}
}
