package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarWatchSubscriptionService {

    private final CalendarParticipationAccess access;
    private final CalendarParticipationRequestStore requests;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarWatchSubscriptionService(
            CalendarParticipationAccess access,
            CalendarParticipationRequestStore requests,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.requests = requests;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarWatchSubscriptionView subscribe(
            CalendarWatchSubscriptionChange command) {
        requireChange(command);
        CalendarParticipationAccess.Target target =
                access.lockVisibleTarget(command.planId(), command.scope());
        return mutate(command, target, true);
    }

    public CalendarWatchSubscriptionView unsubscribe(
            CalendarWatchSubscriptionChange command) {
        requireChange(command);
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        UUID activityId = activityId(command.planId(), command.scope());
        Subscription current = lockCurrent(
                command.planId(),
                activityId,
                command.scope().type(),
                context);
        if (current == null) {
            throw new BusinessException(
                    "CALENDAR_WATCH_SUBSCRIPTION_NOT_FOUND",
                    "No calendar routine subscription exists for this scope");
        }
        CalendarParticipationAccess.Target target =
                new CalendarParticipationAccess.Target(
                        command.planId(),
                        activityId,
                        command.scope().type(),
                        current.sourceOwnerId(),
                        current.shareId(),
                        current.sourceRevision(),
                        context);
        return mutate(command, target, false);
    }

    @Transactional(readOnly = true)
    public Optional<CalendarWatchSubscriptionView> current(
            UUID planId, CalendarParticipationScope scope) {
        if (planId == null || scope == null) {
            throw new IllegalArgumentException(
                    "Calendar watch plan and scope are required");
        }
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        UUID activityId = activityId(planId, scope);
        List<Subscription> rows = jdbc.query(
                """
                SELECT id, plan_id, activity_id, target_scope,
                       subscription_state, subscription_revision,
                       source_revision, share_id,
                       source_created_by_user_id,
                       operation_request_hash, operation_payload_hash
                FROM calendar_routine_subscription
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND subscription_state = 'WATCHING'
                """,
                CalendarWatchSubscriptionService::row,
                planId,
                activityId,
                scope.type().name(),
                context.workspaceId(),
                context.actorId());
        return rows.stream().findFirst()
                .map(CalendarWatchSubscriptionService::view);
    }

    private CalendarWatchSubscriptionView mutate(
            CalendarWatchSubscriptionChange command,
            CalendarParticipationAccess.Target target,
            boolean subscribe) {
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String desired = subscribe ? "WATCHING" : "UNSUBSCRIBED";
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|"
                        + desired);
        String semanticHash = CalendarParticipationAccess.hash(
                "WATCH|"
                        + target.planId()
                        + "|"
                        + target.activityId()
                        + "|"
                        + desired);
        access.lock("watch-request|" + requestHash);
        access.lock("watch-target|"
                + target.context().workspaceId()
                + "|"
                + target.context().actorId()
                + "|"
                + target.planId()
                + "|"
                + target.scopeType()
                + "|"
                + target.activityId());
        CalendarParticipationRequestStore.Receipt receipt =
                requests.find(requestHash, target.context());
        if (receipt != null) {
            CalendarParticipationRequestStore.require(
                    receipt,
                    subscribe ? "WATCH_SUBSCRIBE" : "WATCH_UNSUBSCRIBE",
                    payloadHash);
            return view(receipt);
        }
        Subscription current = lockCurrent(
                target.planId(),
                target.activityId(),
                target.scopeType(),
                target.context());
        if (current != null
                && requestHash.equals(current.requestHash())) {
            requirePayload(current.payloadHash(), payloadHash);
            return view(current);
        }
        if (current == null && command.expectedRevision() != 0) {
            throw revisionConflict();
        }
        if (current != null
                && current.revision() != command.expectedRevision()) {
            throw revisionConflict();
        }
        if (current != null && desired.equals(current.state())) {
            requests.recordWatch(
                    subscribe,
                    requestHash,
                    payloadHash,
                    semanticHash,
                    current.id(),
                    target,
                    current.revision());
            return view(current);
        }
        Instant now = Instant.now(clock);
        if (current == null) {
            UUID id = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO calendar_routine_subscription (
                        id, plan_id, activity_id, share_id, target_scope,
                        subscription_state, subscription_revision,
                        source_revision, operation_request_hash,
                        operation_payload_hash, created_at, updated_at,
                        unsubscribed_at, workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, 'WATCHING', 1, ?, ?, ?, ?, ?,
                            NULL, ?, ?, ?)
                    """,
                    id,
                    target.planId(),
                    target.activityId(),
                    target.shareId(),
                    target.scopeType().name(),
                    target.sourceRevision(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    target.sourceOwnerId());
        } else {
            int updated = jdbc.update(
                    """
                    UPDATE calendar_routine_subscription
                    SET share_id = ?, subscription_state = ?,
                        subscription_revision = subscription_revision + 1,
                        source_revision = ?, operation_request_hash = ?,
                        operation_payload_hash = ?, updated_at = ?,
                        unsubscribed_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                      AND subscription_revision = ?
                    """,
                    target.shareId(),
                    desired,
                    target.sourceRevision(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    subscribe ? null : Timestamp.from(now),
                    current.id(),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    command.expectedRevision());
            if (updated != 1) {
                throw revisionConflict();
            }
        }
        Subscription result = lockCurrent(
                target.planId(),
                target.activityId(),
                target.scopeType(),
                target.context());
        if (result == null) {
            throw new IllegalStateException(
                    "Calendar watch mutation produced no result");
        }
        requests.recordWatch(
                subscribe,
                requestHash,
                payloadHash,
                semanticHash,
                result.id(),
                target,
                result.revision());
        return view(result);
    }

    private Subscription lockCurrent(
            UUID planId,
            UUID activityId,
            CalendarParticipationScopeType scopeType,
            WorkspaceContext context) {
        List<Subscription> rows = jdbc.query(
                """
                SELECT id, plan_id, activity_id, target_scope,
                       subscription_state, subscription_revision,
                       source_revision, share_id,
                       source_created_by_user_id,
                       operation_request_hash, operation_payload_hash
                FROM calendar_routine_subscription
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                CalendarWatchSubscriptionService::row,
                planId,
                activityId,
                scopeType.name(),
                context.workspaceId(),
                context.actorId());
        return rows.stream().findFirst().orElse(null);
    }

    private static Subscription row(
            java.sql.ResultSet row, int ignored)
            throws java.sql.SQLException {
        return new Subscription(
                row.getObject("id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getObject("activity_id", UUID.class),
                CalendarParticipationScopeType.valueOf(
                        row.getString("target_scope")),
                row.getString("subscription_state"),
                row.getLong("subscription_revision"),
                row.getLong("source_revision"),
                row.getObject("share_id", UUID.class),
                row.getObject("source_created_by_user_id", UUID.class),
                row.getString("operation_request_hash"),
                row.getString("operation_payload_hash"));
    }

    private static CalendarWatchSubscriptionView view(
            Subscription row) {
        return new CalendarWatchSubscriptionView(
                row.id(),
                row.planId(),
                new CalendarParticipationScope(
                        row.scopeType(),
                        row.activityId() == null
                                ? row.planId()
                                : row.activityId()),
                "WATCHING".equals(row.state()),
                row.revision());
    }

    private static CalendarWatchSubscriptionView view(
            CalendarParticipationRequestStore.Receipt receipt) {
        return new CalendarWatchSubscriptionView(
                receipt.subscriptionId(),
                receipt.planId(),
                new CalendarParticipationScope(
                        receipt.activityId() == null
                                ? CalendarParticipationScopeType.PLAN
                                : CalendarParticipationScopeType.ACTIVITY,
                        receipt.activityId() == null
                                ? receipt.planId()
                                : receipt.activityId()),
                "WATCHING".equals(receipt.resultState()),
                receipt.resultRevision());
    }

    private static UUID activityId(
            UUID planId, CalendarParticipationScope scope) {
        if (scope.type() == CalendarParticipationScopeType.OCCURRENCE) {
            throw new BusinessException(
                    "CALENDAR_OCCURRENCE_WATCH_NOT_YET_SUPPORTED",
                    "Occurrence watch subscriptions are delivered in Wheel 10");
        }
        if (scope.type() == CalendarParticipationScopeType.PLAN) {
            if (!planId.equals(scope.targetId())) {
                throw new IllegalArgumentException(
                        "Plan watch scope must identify its plan");
            }
            return null;
        }
        return scope.targetId();
    }

    private static void requireChange(
            CalendarWatchSubscriptionChange command) {
        if (command == null
                || command.planId() == null
                || command.scope() == null
                || command.expectedRevision() < 0) {
            throw new IllegalArgumentException(
                    "A watch target and non-negative revision are required");
        }
    }

    private static void requirePayload(
            String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The watch request key was used for another change");
        }
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "CALENDAR_WATCH_REVISION_CONFLICT",
                "Routine subscription changed; reload before updating it");
    }

    private record Subscription(
            UUID id,
            UUID planId,
            UUID activityId,
            CalendarParticipationScopeType scopeType,
            String state,
            long revision,
            long sourceRevision,
            UUID shareId,
            UUID sourceOwnerId,
            String requestHash,
            String payloadHash) {}
}
