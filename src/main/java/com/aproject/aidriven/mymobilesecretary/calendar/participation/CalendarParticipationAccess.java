package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class CalendarParticipationAccess {

    private final JdbcTemplate jdbc;

    CalendarParticipationAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Target lockVisibleTarget(
            UUID planId, CalendarParticipationScope scope) {
        if (planId == null || scope == null) {
            throw new IllegalArgumentException(
                    "Calendar participation plan and scope are required");
        }
        if (scope.type() == CalendarParticipationScopeType.OCCURRENCE) {
            throw new BusinessException(
                    "CALENDAR_OCCURRENCE_PARTICIPATION_NOT_YET_SUPPORTED",
                    "Occurrence participation is delivered in Wheel 10");
        }
        if (scope.type() == CalendarParticipationScopeType.PLAN
                && !planId.equals(scope.targetId())) {
            throw new IllegalArgumentException(
                    "Plan participation scope must identify its plan");
        }
        WorkspaceContext context = tenantContext();
        List<PlanRow> plans = jdbc.query(
                """
                SELECT id, title, created_by_user_id, version
                FROM calendar_plan
                WHERE id = ? AND workspace_id = ? AND status = 'ACTIVE'
                """,
                (row, ignored) -> new PlanRow(
                        row.getObject("id", UUID.class),
                        row.getObject("created_by_user_id", UUID.class),
                        row.getLong("version") + 1),
                planId,
                context.workspaceId());
        if (plans.size() != 1) {
            throw new NotFoundException("Calendar plan", "requested plan");
        }
        PlanRow plan = plans.getFirst();
        UUID activityId = scope.type()
                        == CalendarParticipationScopeType.ACTIVITY
                ? scope.targetId()
                : null;
        if (activityId != null) {
            Long activities = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM calendar_activity
                    WHERE id = ? AND plan_id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                    """,
                    Long.class,
                    activityId,
                    planId,
                    context.workspaceId(),
                    plan.ownerId());
            if (activities == null || activities != 1) {
                throw new NotFoundException(
                        "Calendar activity", "requested activity");
            }
        }
        UUID shareId = context.actorId().equals(plan.ownerId())
                ? null
                : lockEffectiveShare(plan, activityId, context);
        return new Target(
                plan.id(),
                activityId,
                scope.type(),
                plan.ownerId(),
                shareId,
                plan.revision(),
                context);
    }

    private UUID lockEffectiveShare(
            PlanRow plan, UUID activityId, WorkspaceContext context) {
        String sql = activityId == null
                ? """
                SELECT share_row.id
                FROM calendar_share share_row
                WHERE share_row.plan_id = ?
                  AND share_row.workspace_id = ?
                  AND share_row.created_by_user_id = ?
                  AND share_row.grantee_user_id = ?
                  AND share_row.status = 'ACTIVE'
                  AND share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                ORDER BY share_row.id
                """
                : """
                SELECT share_row.id
                FROM calendar_share share_row
                WHERE share_row.plan_id = ?
                  AND share_row.workspace_id = ?
                  AND share_row.created_by_user_id = ?
                  AND share_row.grantee_user_id = ?
                  AND share_row.status = 'ACTIVE'
                  AND (
                    share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                    OR (
                      share_row.scope_mode = 'SELECTED_ACTIVITIES'
                      AND EXISTS (
                        SELECT 1
                        FROM calendar_share_scope_item item
                        WHERE item.snapshot_id =
                                share_row.current_scope_snapshot_id
                          AND item.share_id = share_row.id
                          AND item.target_kind = 'ACTIVITY'
                          AND item.activity_id = ?)))
                ORDER BY share_row.id
                """;
        Object[] arguments = activityId == null
                ? new Object[] {
                    plan.id(),
                    context.workspaceId(),
                    plan.ownerId(),
                    context.actorId()
                }
                : new Object[] {
                    plan.id(),
                    context.workspaceId(),
                    plan.ownerId(),
                    context.actorId(),
                    activityId
                };
        List<UUID> shares = jdbc.query(
                sql,
                (row, ignored) -> row.getObject("id", UUID.class),
                arguments);
        if (shares.isEmpty()) {
            throw new NotFoundException("Calendar plan", "requested plan");
        }
        UUID shareId = shares.getFirst();
        lock("calendar-share-lifecycle|" + shareId);
        Long active = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_share
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND grantee_user_id = ?
                  AND status = 'ACTIVE'
                """,
                Long.class,
                shareId,
                plan.id(),
                context.workspaceId(),
                plan.ownerId(),
                context.actorId());
        if (active == null || active != 1) {
            throw new NotFoundException("Calendar plan", "requested plan");
        }
        return shareId;
    }

    void lock(String value) {
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)) IS NULL
                """,
                Boolean.class,
                value);
    }

    static String hash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Request identity is required");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.strip().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar participation requires tenant scope");
        }
        return context;
    }

    record Target(
            UUID planId,
            UUID activityId,
            CalendarParticipationScopeType scopeType,
            UUID sourceOwnerId,
            UUID shareId,
            long sourceRevision,
            WorkspaceContext context) {}

    private record PlanRow(UUID id, UUID ownerId, long revision) {}
}
