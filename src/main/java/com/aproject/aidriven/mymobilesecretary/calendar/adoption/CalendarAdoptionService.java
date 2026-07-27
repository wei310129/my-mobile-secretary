package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarAdoptionService {

    private final CalendarApplicationService calendars;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CalendarAdoptionService(
            CalendarApplicationService calendars,
            JdbcTemplate jdbc,
            ApplicationEventPublisher events,
            Clock clock) {
        this.calendars = calendars;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    public CalendarAdoptionView adopt(String planKey, List<String> selectedNodeKeys) {
        LinkedHashSet<String> keys = validatedKeys(selectedNodeKeys);
        WorkspaceContext context = tenantContext();
        List<CalendarTimeNodeEntity> selected = keys.stream()
                .map(key -> calendars.lockNodeForReminder(planKey, key))
                .toList();
        UUID planId = selected.getFirst().getPlanId();
        if (selected.stream().anyMatch(node -> !planId.equals(node.getPlanId()))) {
            throw new SecurityException("Adoption nodes must belong to one plan");
        }
        return persist(
                planId,
                context.actorId(),
                selected.stream()
                        .map(node -> new SelectedNode(node.getId(), node.getRevision()))
                        .toList(),
                calendars.getPlan(planKey).title(),
                context);
    }

    public CalendarAdoptionView adoptPlan(
            UUID planId, List<String> selectedNodeKeys) {
        if (planId == null) {
            throw new IllegalArgumentException("Calendar plan id is required");
        }
        LinkedHashSet<String> keys = validatedKeys(selectedNodeKeys);
        WorkspaceContext context = tenantContext();
        SourcePlan plan = visibleActivePlan(planId, context);
        if (!plan.sourceOwnerId().equals(context.actorId())) {
            requireAndLockWholePlanShare(planId, plan.sourceOwnerId(), context);
        }
        List<SelectedNode> selected = keys.stream()
                .map(key -> visibleNode(planId, plan.sourceOwnerId(), key, context))
                .toList();
        return persist(
                planId,
                plan.sourceOwnerId(),
                selected,
                plan.title(),
                context);
    }

    private CalendarAdoptionView persist(
            UUID planId,
            UUID sourceOwnerId,
            List<SelectedNode> selected,
            String title,
            WorkspaceContext context) {
        Long active = jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_adoption
                WHERE plan_id = ? AND status = 'ACTIVE'
                    AND workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                planId,
                context.workspaceId(),
                context.actorId());
        if (active != null && active > 0) {
            throw new BusinessException(
                    "CALENDAR_ADOPTION_EXISTS",
                    "This calendar plan already has an active adoption snapshot");
        }
        UUID adoptionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                INSERT INTO calendar_adoption (
                    id, plan_id, status, revision, version, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, 'ACTIVE', 1, 0, ?, ?, ?, ?, ?)
                """,
                adoptionId,
                planId,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                sourceOwnerId);
        for (SelectedNode node : selected) {
            jdbc.update(
                    """
                    INSERT INTO calendar_adoption_node (
                        adoption_id, plan_id, node_id, node_revision, created_at,
                        workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    adoptionId,
                    planId,
                    node.id(),
                    node.revision(),
                    Timestamp.from(now),
                    context.workspaceId(),
                    context.actorId(),
                    sourceOwnerId);
        }
        materializeInitialSnapshot(
                adoptionId, planId, sourceOwnerId, now, context);
        jdbc.update(
                """
                INSERT INTO calendar_adoption_history (
                    id, adoption_id, plan_id, event_type, adoption_revision,
                    occurred_at, workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, 'ADOPTED', 1, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                adoptionId,
                planId,
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                sourceOwnerId);
        events.publishEvent(new CalendarAdoptionCreatedEvent(
                adoptionId, title, selected.size(), now));
        return new CalendarAdoptionView(1, selected.size());
    }

    @Transactional(readOnly = true)
    public List<PersonalRouteConstraint> constraints() {
        WorkspaceContext context = tenantContext();
        return jdbc.query(
                """
                SELECT node.node_key, node.resolved_time,
                    node.location_label, node.latitude, node.longitude,
                    node.adjustability, node.source_node_revision
                FROM calendar_personal_projection_snapshot snapshot
                JOIN calendar_adoption adoption
                  ON adoption.id = snapshot.adoption_id
                 AND adoption.workspace_id = snapshot.workspace_id
                 AND adoption.created_by_user_id =
                     snapshot.created_by_user_id
                JOIN calendar_personal_projection_node node
                  ON node.snapshot_id = snapshot.id
                 AND node.adoption_id = snapshot.adoption_id
                 AND node.workspace_id = snapshot.workspace_id
                 AND node.created_by_user_id =
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
                    AND node.cancellation_status = 'ACTIVE'
                    AND NOT EXISTS (
                        SELECT 1
                        FROM calendar_projection_suppression suppression
                        WHERE suppression.plan_id = snapshot.plan_id
                          AND suppression.workspace_id =
                              snapshot.workspace_id
                          AND suppression.created_by_user_id =
                              snapshot.created_by_user_id
                          AND suppression.status = 'ACTIVE'
                          AND (
                            suppression.target_scope = 'PLAN'
                            OR (
                              suppression.target_scope = 'ACTIVITY'
                              AND suppression.activity_id =
                                  node.activity_id)))
                ORDER BY node.resolved_time, node.node_key
                """,
                (row, ignored) -> new PersonalRouteConstraint(
                        row.getString("node_key"),
                        row.getTimestamp("resolved_time").toInstant(),
                        row.getString("location_label") == null
                                ? null
                                : new CalendarLocation(
                                        row.getString("location_label"),
                                        row.getDouble("latitude"),
                                        row.getDouble("longitude")),
                        com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability
                                .valueOf(row.getString("adjustability")),
                        row.getLong("source_node_revision")),
                context.workspaceId(),
                context.actorId());
    }

    private void materializeInitialSnapshot(
            UUID adoptionId,
            UUID planId,
            UUID sourceOwnerId,
            Instant now,
            WorkspaceContext context) {
        UUID snapshotId = UUID.randomUUID();
        int snapshot = jdbc.update(
                """
                INSERT INTO calendar_personal_projection_snapshot (
                    id, adoption_id, participation_id, plan_id,
                    plan_title, placement_kind, timed_start, timed_end,
                    zone_id, all_day_start, all_day_end_exclusive,
                    source_signal_id, source_signal_kind,
                    source_signal_owner_user_id,
                    source_signal_recipient_user_id,
                    projection_revision, source_plan_revision,
                    projection_status, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                SELECT ?, ?, participation.id, plan.id,
                       plan.title, plan.placement_kind,
                       plan.timed_start, plan.timed_end, plan.zone_id,
                       plan.all_day_start, plan.all_day_end_exclusive,
                       NULL, NULL, NULL, NULL, 1, plan.version + 1,
                       'ACTIVE', ?, plan.workspace_id, ?, ?
                FROM calendar_plan plan
                LEFT JOIN calendar_participation participation
                  ON participation.plan_id = plan.id
                 AND participation.activity_id IS NULL
                 AND participation.target_scope = 'PLAN'
                 AND participation.workspace_id = plan.workspace_id
                 AND participation.created_by_user_id = ?
                 AND participation.source_created_by_user_id =
                     plan.created_by_user_id
                WHERE plan.id = ? AND plan.workspace_id = ?
                  AND plan.created_by_user_id = ?
                """,
                snapshotId,
                adoptionId,
                Timestamp.from(now),
                context.actorId(),
                sourceOwnerId,
                context.actorId(),
                planId,
                context.workspaceId(),
                sourceOwnerId);
        if (snapshot != 1) {
            throw new IllegalStateException(
                    "Calendar adoption could not materialize its personal snapshot");
        }
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_node (
                    snapshot_id, adoption_id, plan_id, source_node_id,
                    activity_id, node_key, label, expression_kind,
                    absolute_time, offset_seconds, base_node_key,
                    resolved_time, criticality, adjustability,
                    location_label, latitude, longitude,
                    cancellation_status, canceled_at,
                    source_node_revision, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                SELECT ?, selected.adoption_id, selected.plan_id,
                       node.id, node.activity_id, node.node_key, node.label,
                       node.expression_kind, node.absolute_time,
                       node.offset_seconds, node.base_node_key,
                       node.resolved_time, node.criticality,
                       node.adjustability, node.location_label,
                       node.latitude, node.longitude,
                       node.cancellation_status, node.canceled_at,
                       selected.node_revision, ?, selected.workspace_id,
                       selected.created_by_user_id,
                       selected.source_created_by_user_id
                FROM calendar_adoption_node selected
                JOIN calendar_time_node node
                  ON node.id = selected.node_id
                 AND node.plan_id = selected.plan_id
                 AND node.workspace_id = selected.workspace_id
                 AND node.created_by_user_id =
                     selected.source_created_by_user_id
                WHERE selected.adoption_id = ?
                  AND selected.workspace_id = ?
                  AND selected.created_by_user_id = ?
                """,
                snapshotId,
                Timestamp.from(now),
                adoptionId,
                context.workspaceId(),
                context.actorId());
    }

    private SourcePlan visibleActivePlan(
            UUID planId, WorkspaceContext context) {
        List<SourcePlan> rows = jdbc.query(
                """
                SELECT id, title, created_by_user_id
                FROM calendar_plan
                WHERE id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                CalendarAdoptionService::sourcePlan,
                planId,
                context.workspaceId());
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar plan", "requested plan");
        }
        return rows.getFirst();
    }

    private void requireAndLockWholePlanShare(
            UUID planId, UUID sourceOwnerId, WorkspaceContext context) {
        List<UUID> shares = jdbc.query(
                """
                SELECT id
                FROM calendar_share
                WHERE plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND grantee_user_id = ?
                  AND permission IN ('VIEWER', 'EDITOR')
                  AND scope_mode = 'LIVE_WHOLE_PLAN'
                  AND status = 'ACTIVE'
                """,
                (row, ignored) -> row.getObject("id", UUID.class),
                planId,
                context.workspaceId(),
                sourceOwnerId,
                context.actorId());
        if (shares.size() != 1) {
            throw new NotFoundException("Calendar plan", "requested plan");
        }
        lockIdentity("calendar-share-lifecycle|" + shares.getFirst());
        Long active = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_share
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND grantee_user_id = ?
                  AND permission IN ('VIEWER', 'EDITOR')
                  AND scope_mode = 'LIVE_WHOLE_PLAN'
                  AND status = 'ACTIVE'
                """,
                Long.class,
                shares.getFirst(),
                planId,
                context.workspaceId(),
                sourceOwnerId,
                context.actorId());
        if (active == null || active != 1) {
            throw new NotFoundException("Calendar plan", "requested plan");
        }
    }

    private SelectedNode visibleNode(
            UUID planId,
            UUID sourceOwnerId,
            String nodeKey,
            WorkspaceContext context) {
        List<SelectedNode> rows = jdbc.query(
                """
                SELECT id, revision
                FROM calendar_time_node
                WHERE plan_id = ? AND node_key = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND cancellation_status = 'ACTIVE'
                """,
                (row, ignored) -> new SelectedNode(
                        row.getObject("id", UUID.class),
                        row.getLong("revision")),
                planId,
                nodeKey,
                context.workspaceId(),
                sourceOwnerId);
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar node", "requested node");
        }
        SelectedNode selected = rows.getFirst();
        lockIdentity("calendar-source-node|" + selected.id());
        Long current = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_time_node
                WHERE id = ? AND plan_id = ? AND node_key = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND revision = ?
                  AND cancellation_status = 'ACTIVE'
                """,
                Long.class,
                selected.id(),
                planId,
                nodeKey,
                context.workspaceId(),
                sourceOwnerId,
                selected.revision());
        if (current == null || current != 1) {
            throw new NotFoundException("Calendar node", "requested node");
        }
        return selected;
    }

    private void lockIdentity(String identity) {
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)) IS NULL
                """,
                Boolean.class,
                identity);
    }

    private static LinkedHashSet<String> validatedKeys(
            List<String> selectedNodeKeys) {
        List<String> requested =
                selectedNodeKeys == null ? List.of() : List.copyOf(selectedNodeKeys);
        LinkedHashSet<String> keys = new LinkedHashSet<>(requested);
        if (keys.isEmpty() || keys.size() != requested.size()) {
            throw new IllegalArgumentException(
                    "Adoption requires a non-empty unique node selection");
        }
        return keys;
    }

    private static SourcePlan sourcePlan(ResultSet row, int ignored)
            throws SQLException {
        return new SourcePlan(
                row.getObject("id", UUID.class),
                row.getString("title"),
                row.getObject("created_by_user_id", UUID.class));
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar adoption requires a tenant workspace");
        }
        return context;
    }

    private record SourcePlan(UUID id, String title, UUID sourceOwnerId) {}

    private record SelectedNode(UUID id, long revision) {}
}
