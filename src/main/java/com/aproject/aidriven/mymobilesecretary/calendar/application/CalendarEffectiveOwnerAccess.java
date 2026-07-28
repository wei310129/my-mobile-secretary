package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class CalendarEffectiveOwnerAccess {

    private final JdbcTemplate jdbc;

    public CalendarEffectiveOwnerAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Scope require(UUID planId, WorkspaceContext context) {
        requirePlanId(planId);
        requireTenantContext(context);
        return requireEffectiveOwner(queryByPlanId(planId, context, false), context);
    }

    public Scope requireAndLockPlan(UUID planId, WorkspaceContext context) {
        requirePlanId(planId);
        requireTenantContext(context);
        lockOwnershipTransfer(planId);
        return requireEffectiveOwner(queryByPlanId(planId, context, true), context);
    }

    public Scope requireByCreationRequestHash(
            String requestHash, WorkspaceContext context) {
        requireRequestHash(requestHash);
        requireTenantContext(context);
        return requireEffectiveOwner(
                queryByRequestHash(requestHash, context, false), context);
    }

    public Scope requireAndLockPlanByCreationRequestHash(
            String requestHash, WorkspaceContext context) {
        requireRequestHash(requestHash);
        requireTenantContext(context);
        Scope initial = requireEffectiveOwner(
                queryByRequestHash(requestHash, context, false), context);
        lockOwnershipTransfer(initial.planId());
        Scope locked = requireEffectiveOwner(
                queryByRequestHash(requestHash, context, true), context);
        if (!initial.planId().equals(locked.planId())) {
            throw ambiguousRequestKey();
        }
        return locked;
    }

    private List<Scope> queryByPlanId(
            UUID planId, WorkspaceContext context, boolean lockPlan) {
        return query(
                """
                WHERE plan.id = ? AND plan.workspace_id = ?
                """
                        + lockClause(lockPlan),
                context,
                planId,
                context.workspaceId());
    }

    private List<Scope> queryByRequestHash(
            String requestHash, WorkspaceContext context, boolean lockPlan) {
        return query(
                """
                WHERE plan.creation_request_hash = ?
                  AND plan.workspace_id = ?
                """
                        + lockClause(lockPlan),
                context,
                requestHash,
                context.workspaceId());
    }

    private List<Scope> query(
            String predicate,
            WorkspaceContext context,
            Object... arguments) {
        return jdbc.query(
                """
                SELECT plan.id,
                       plan.created_by_user_id AS source_owner_id,
                       ownership.owner_user_id AS effective_owner_id,
                       ownership.ownership_revision
                FROM calendar_plan plan
                LEFT JOIN calendar_plan_ownership ownership
                  ON ownership.plan_id = plan.id
                 AND ownership.workspace_id = plan.workspace_id
                 AND ownership.source_created_by_user_id =
                        plan.created_by_user_id
                """
                        + predicate,
                (row, ignored) -> {
                    UUID sourceOwnerId =
                            row.getObject("source_owner_id", UUID.class);
                    UUID effectiveOwnerId = Objects.requireNonNullElse(
                            row.getObject("effective_owner_id", UUID.class),
                            sourceOwnerId);
                    Long ownershipRevision =
                            row.getObject("ownership_revision", Long.class);
                    return new Scope(
                            row.getObject("id", UUID.class),
                            context.actorId(),
                            effectiveOwnerId,
                            sourceOwnerId,
                            ownershipRevision == null ? 0 : ownershipRevision);
                },
                arguments);
    }

    private Scope requireEffectiveOwner(
            List<Scope> rows, WorkspaceContext context) {
        List<Scope> owned = rows.stream()
                .filter(row -> context.actorId().equals(row.effectiveOwnerId()))
                .toList();
        if (owned.isEmpty()) {
            if (rows.isEmpty()) {
                throw new NotFoundException(
                        "Calendar plan", "requested plan");
            }
            throw new SecurityException(
                    "Only the effective calendar owner may perform this action");
        }
        if (owned.size() != 1) {
            throw ambiguousRequestKey();
        }
        return owned.getFirst();
    }

    private void lockOwnershipTransfer(UUID planId) {
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)) IS NULL
                """,
                Boolean.class,
                "ownership-transfer-plan|" + planId);
    }

    private static String lockClause(boolean lockPlan) {
        return lockPlan ? "FOR UPDATE OF plan" : "";
    }

    private static BusinessException ambiguousRequestKey() {
        return new BusinessException(
                "AMBIGUOUS_CALENDAR_REQUEST_KEY",
                "The calendar request key identifies more than one owned plan");
    }

    private static void requirePlanId(UUID planId) {
        if (planId == null) {
            throw new IllegalArgumentException("Calendar plan id is required");
        }
    }

    private static void requireRequestHash(String requestHash) {
        if (requestHash == null || requestHash.isBlank()) {
            throw new IllegalArgumentException(
                    "Calendar creation request hash is required");
        }
    }

    private static void requireTenantContext(WorkspaceContext context) {
        if (context == null || !context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar owner access requires a tenant workspace");
        }
    }

    public record Scope(
            UUID planId,
            UUID actorId,
            UUID effectiveOwnerId,
            UUID sourceOwnerId,
            long ownershipRevision) {}
}
