package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarPlanLifecycleService {

    private final JdbcTemplate jdbc;
    private final CalendarEffectiveOwnerAccess ownerAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CalendarPlanLifecycleService(
            JdbcTemplate jdbc,
            CalendarEffectiveOwnerAccess ownerAccess,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.ownerAccess = ownerAccess;
        this.events = events;
        this.clock = clock;
    }

    public CalendarPlanLifecycleView cancel(UUID planId, long expectedRevision) {
        WorkspaceContext context = context();
        CalendarEffectiveOwnerAccess.Scope owner =
                ownerAccess.requireAndLockPlan(planId, context);
        LockedPlan plan = lock(planId, owner.sourceOwnerId(), context);
        requireRevision(plan, expectedRevision);
        if (plan.status() != CalendarPlanStatus.ACTIVE) {
            throw new BusinessException(
                    "CALENDAR_PLAN_NOT_ACTIVE",
                    "Only an active calendar plan can be canceled");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        updatePlan(
                plan,
                CalendarPlanStatus.CANCELED,
                now,
                owner.sourceOwnerId(),
                context);
        closeDependents(plan.id(), now, owner.sourceOwnerId(), context);
        events.publishEvent(new CalendarPlanLifecycleEvent(
                plan.id(),
                plan.title(),
                CalendarPlanLifecycleEvent.Action.CANCELED,
                now));
        return view(plan, CalendarPlanStatus.CANCELED, now, null);
    }

    public CalendarPlanLifecycleView archive(UUID planId, long expectedRevision) {
        WorkspaceContext context = context();
        CalendarEffectiveOwnerAccess.Scope owner =
                ownerAccess.requireAndLockPlan(planId, context);
        LockedPlan plan = lock(planId, owner.sourceOwnerId(), context);
        requireRevision(plan, expectedRevision);
        if (plan.status() == CalendarPlanStatus.ARCHIVED) {
            throw new BusinessException(
                    "CALENDAR_PLAN_ALREADY_ARCHIVED",
                    "Calendar plan is already archived");
        }
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        updatePlan(
                plan,
                CalendarPlanStatus.ARCHIVED,
                now,
                owner.sourceOwnerId(),
                context);
        closeDependents(plan.id(), now, owner.sourceOwnerId(), context);
        events.publishEvent(new CalendarPlanLifecycleEvent(
                plan.id(),
                plan.title(),
                CalendarPlanLifecycleEvent.Action.ARCHIVED,
                now));
        return view(
                plan,
                CalendarPlanStatus.ARCHIVED,
                plan.canceledAt(),
                now);
    }

    private LockedPlan lock(
            UUID planId,
            UUID sourceOwnerId,
            WorkspaceContext context) {
        List<LockedPlan> rows = jdbc.query(
                """
                SELECT id, title, status, version, canceled_at, archived_at
                FROM calendar_plan
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, index) -> new LockedPlan(
                        row.getObject("id", UUID.class),
                        row.getString("title"),
                        CalendarPlanStatus.valueOf(row.getString("status")),
                        row.getLong("version") + 1,
                        instant(row.getTimestamp("canceled_at")),
                        instant(row.getTimestamp("archived_at"))),
                planId,
                context.workspaceId(),
                sourceOwnerId);
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar plan", "requested plan");
        }
        return rows.getFirst();
    }

    private void updatePlan(
            LockedPlan plan,
            CalendarPlanStatus status,
            Instant now,
            UUID sourceOwnerId,
            WorkspaceContext context) {
        Instant canceledAt =
                status == CalendarPlanStatus.CANCELED ? now : plan.canceledAt();
        int changed = jdbc.update(
                """
                UPDATE calendar_plan
                SET status = ?, canceled_at = ?, archived_at = ?,
                    version = version + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND version = ?
                """,
                status.name(),
                timestamp(canceledAt),
                status == CalendarPlanStatus.ARCHIVED
                        ? Timestamp.from(now)
                        : null,
                Timestamp.from(now),
                plan.id(),
                context.workspaceId(),
                sourceOwnerId,
                plan.revision() - 1);
        if (changed != 1) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar plan changed; reload it before changing lifecycle");
        }
    }

    private void closeDependents(
            UUID planId,
            Instant now,
            UUID sourceOwnerId,
            WorkspaceContext context) {
        Object[] scope = {
            Timestamp.from(now),
            planId,
            context.workspaceId(),
            sourceOwnerId
        };
        jdbc.update(
                """
                UPDATE calendar_reminder_occurrence
                SET status = 'CANCELED', version = version + 1, updated_at = ?
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status IN ('PENDING', 'ENQUEUED')
                """,
                scope);
        jdbc.update(
                """
                UPDATE calendar_reminder_rule
                SET status = 'CANCELED', revision = revision + 1,
                    version = version + 1, updated_at = ?
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE'
                """,
                scope);
        jdbc.update(
                """
                UPDATE calendar_adoption
                SET status = 'CANCELED', revision = revision + 1,
                    version = version + 1, updated_at = ?
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE'
                """,
                scope);
        archiveBinding("calendar_knowledge_fact_binding", scope);
        archiveBinding("calendar_knowledge_annotation_binding", scope);
        jdbc.update(
                """
                UPDATE calendar_attachment_binding
                SET status = 'UNLINKED', binding_revision = binding_revision + 1,
                    updated_at = ?
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE'
                """,
                scope);
        jdbc.update(
                """
                UPDATE calendar_knowledge_excerpt
                SET status = 'REVOKED', revoked_at = ?,
                    row_revision = row_revision + 1, updated_at = ?
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status <> 'REVOKED'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                planId,
                context.workspaceId(),
                sourceOwnerId);
    }

    private void archiveBinding(String table, Object[] scope) {
        jdbc.update(
                """
                UPDATE %s
                SET status = 'ARCHIVED', binding_revision = binding_revision + 1,
                    updated_at = ?
                WHERE plan_id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE'
                """
                        .formatted(table),
                scope);
    }

    private static void requireRevision(LockedPlan plan, long expectedRevision) {
        if (expectedRevision <= 0 || plan.revision() != expectedRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar plan changed; reload it before changing lifecycle");
        }
    }

    private static CalendarPlanLifecycleView view(
            LockedPlan plan,
            CalendarPlanStatus status,
            Instant canceledAt,
            Instant archivedAt) {
        return new CalendarPlanLifecycleView(
                plan.id(), status, plan.revision() + 1, canceledAt, archivedAt);
    }

    private static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar plan lifecycle requires a tenant workspace");
        }
        return context;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record LockedPlan(
            UUID id,
            String title,
            CalendarPlanStatus status,
            long revision,
            Instant canceledAt,
            Instant archivedAt) {
    }
}
