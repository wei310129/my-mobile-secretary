package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class CalendarRegistrationAccess {

    private final JdbcTemplate jdbc;

    CalendarRegistrationAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Target lockTarget(UUID planId, CalendarParticipationScope scope) {
        if (planId == null || scope == null) {
            throw new IllegalArgumentException(
                    "Calendar registration plan and scope are required");
        }
        if (scope.type() == CalendarParticipationScopeType.OCCURRENCE) {
            throw new BusinessException(
                    "CALENDAR_OCCURRENCE_REGISTRATION_NOT_YET_SUPPORTED",
                    "Occurrence registration is delivered in Wheel 10");
        }
        if (scope.type() == CalendarParticipationScopeType.PLAN
                && !planId.equals(scope.targetId())) {
            throw new IllegalArgumentException(
                    "Plan registration scope must identify its plan");
        }
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        List<PlanRow> plans = jdbc.query(
                """
                SELECT plan.id, plan.created_by_user_id,
                       ownership.owner_user_id,
                       ownership.ownership_revision
                FROM calendar_plan plan
                LEFT JOIN calendar_plan_ownership ownership
                  ON ownership.plan_id = plan.id
                 AND ownership.workspace_id = plan.workspace_id
                 AND ownership.source_created_by_user_id =
                        plan.created_by_user_id
                WHERE plan.id = ? AND plan.workspace_id = ?
                  AND plan.status = 'ACTIVE'
                """,
                (row, ignored) -> new PlanRow(
                        row.getObject("id", UUID.class),
                        row.getObject("created_by_user_id", UUID.class),
                        java.util.Objects.requireNonNullElse(
                                row.getObject("owner_user_id", UUID.class),
                                row.getObject(
                                        "created_by_user_id", UUID.class)),
                        row.getLong("ownership_revision")),
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
            Long count = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM calendar_activity
                    WHERE id = ? AND plan_id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                    """,
                    Long.class,
                    activityId,
                    plan.id(),
                    context.workspaceId(),
                    plan.sourceOwnerId());
            if (count == null || count != 1) {
                throw new NotFoundException(
                        "Calendar activity", "requested activity");
            }
        }
        lock("registration-plan|" + planId);
        return new Target(
                plan.id(),
                activityId,
                scope.type(),
                plan.sourceOwnerId(),
                plan.effectiveOwnerId(),
                plan.ownershipRevision(),
                context);
    }

    void requireOwner(Target target) {
        if (!target.context()
                .actorId()
                .equals(target.effectiveOwnerId())) {
            throw new SecurityException(
                    "Only the effective calendar owner may perform this action");
        }
    }

    void requireManager(Target target, boolean rosterRequired) {
        if (target.context()
                .actorId()
                .equals(target.effectiveOwnerId())) {
            return;
        }
        Long assigned = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_organizer_assignment assignment
                WHERE assignment.plan_id = ?
                  AND assignment.activity_id IS NOT DISTINCT FROM ?
                  AND assignment.workspace_id = ?
                  AND assignment.source_created_by_user_id = ?
                  AND assignment.manager_user_id = ?
                  AND assignment.assignment_status = 'ACTIVE'
                  AND (? = FALSE OR assignment.roster_permission)
                """,
                Long.class,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                target.context().actorId(),
                rosterRequired);
        if (assigned == null || assigned != 1) {
            throw new SecurityException(
                    "A scoped participant-manager assignment is required");
        }
    }

    void requireActiveMember(UUID userId, WorkspaceContext context) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM workspace_member member
                JOIN app_user actor ON actor.id = member.user_id
                WHERE member.workspace_id = ?
                  AND member.user_id = ?
                  AND actor.status = 'ACTIVE'
                """,
                Long.class,
                context.workspaceId(),
                userId);
        if (count == null || count != 1) {
            throw new BusinessException(
                    "CALENDAR_MANAGER_NOT_ACTIVE_MEMBER",
                    "Participant managers must be active workspace members");
        }
    }

    void lock(String identity) {
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)) IS NULL
                """,
                Boolean.class,
                identity);
    }

    record Target(
            UUID planId,
            UUID activityId,
            CalendarParticipationScopeType scopeType,
            UUID sourceOwnerId,
            UUID effectiveOwnerId,
            long ownershipRevision,
            WorkspaceContext context) {

        CalendarParticipationScope scope() {
            return new CalendarParticipationScope(
                    scopeType,
                    activityId == null ? planId : activityId);
        }
    }

    private record PlanRow(
            UUID id,
            UUID sourceOwnerId,
            UUID effectiveOwnerId,
            long ownershipRevision) {}
}
