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
public class CalendarParticipationService {

    private final CalendarParticipationAccess access;
    private final CalendarParticipationRequestStore requests;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarParticipationService(
            CalendarParticipationAccess access,
            CalendarParticipationRequestStore requests,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.requests = requests;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarParticipationView change(
            CalendarParticipationChange command) {
        requireChange(command);
        CalendarParticipationAccess.Target target =
                access.lockVisibleTarget(command.planId(), command.scope());
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|"
                        + command.status());
        String semanticHash = CalendarParticipationAccess.hash(
                "PARTICIPATION|"
                        + target.planId()
                        + "|"
                        + target.activityId()
                        + "|"
                        + command.status());
        access.lock("participation-request|" + requestHash);
        access.lock("participation-target|"
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
                    receipt, "PARTICIPATION", payloadHash);
            return view(receipt);
        }
        ParticipationRow replay = findByRequest(requestHash, target.context());
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            requests.recordParticipation(
                    requestHash,
                    payloadHash,
                    semanticHash,
                    replay.id(),
                    target,
                    replay.status(),
                    replay.revision());
            return view(replay);
        }

        ParticipationRow current = lockCurrent(target);
        if (current != null
                && current.status() == command.status()) {
            requests.recordParticipation(
                    requestHash,
                    payloadHash,
                    semanticHash,
                    current.id(),
                    target,
                    current.status(),
                    current.revision());
            return view(current);
        }
        if (current == null && command.expectedRevision() != 0) {
            throw revisionConflict();
        }
        if (current != null
                && current.revision() != command.expectedRevision()) {
            throw revisionConflict();
        }

        Policy policy = currentPolicy(target);
        requireConfirmedExit(current, policy, command.status());
        Instant now = Instant.now(clock);
        UUID id = current == null ? UUID.randomUUID() : current.id();
        long revision = current == null ? 1 : current.revision() + 1;
        if (current == null) {
            jdbc.update(
                    """
                    INSERT INTO calendar_participation (
                        id, plan_id, activity_id, share_id, target_scope,
                        participation_policy, applied_policy_revision,
                        participation_state, participation_revision,
                        source_revision, operation_request_hash,
                        operation_payload_hash, created_at, updated_at,
                        workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    target.planId(),
                    target.activityId(),
                    target.shareId(),
                    target.scopeType().name(),
                    policy.policy().name(),
                    policy.revision(),
                    command.status().name(),
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
                    UPDATE calendar_participation
                    SET participation_state = ?,
                        participation_revision = participation_revision + 1,
                        source_revision = ?, operation_request_hash = ?,
                        operation_payload_hash = ?, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                      AND participation_revision = ?
                    """,
                    command.status().name(),
                    target.sourceRevision(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    current.id(),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    command.expectedRevision());
            if (updated != 1) {
                throw revisionConflict();
            }
        }
        jdbc.update(
                """
                INSERT INTO calendar_participation_history (
                    id, participation_id, plan_id, activity_id,
                    target_scope, event_type, previous_state,
                    current_state, participation_revision,
                    source_revision, operation_request_hash,
                    operation_payload_hash, occurred_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                id,
                target.planId(),
                target.activityId(),
                target.scopeType().name(),
                current == null ? "PARTICIPATION_DECLARED" : "STATE_CHANGED",
                current == null ? null : current.status().name(),
                command.status().name(),
                revision,
                target.sourceRevision(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        requests.recordParticipation(
                requestHash,
                payloadHash,
                semanticHash,
                id,
                target,
                command.status(),
                revision);
        return current(command.planId(), command.scope()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<CalendarParticipationView> current(
            UUID planId, CalendarParticipationScope scope) {
        if (planId == null || scope == null) {
            throw new IllegalArgumentException(
                    "Calendar participation plan and scope are required");
        }
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        UUID activityId = activityId(planId, scope);
        List<ParticipationRow> rows = jdbc.query(
                """
                SELECT participation.id, participation.plan_id,
                       participation.activity_id,
                       participation.target_scope,
                       participation.participation_state,
                       participation.participation_revision,
                       participation.operation_payload_hash,
                       latest_receipt.disposition,
                       latest_receipt.acknowledgement_status
                FROM calendar_participation participation
                LEFT JOIN LATERAL (
                    SELECT receipt.disposition,
                           receipt.acknowledgement_status
                    FROM calendar_authoritative_recipient_receipt receipt
                    WHERE receipt.participation_id = participation.id
                      AND receipt.workspace_id =
                          participation.workspace_id
                      AND receipt.created_by_user_id =
                          participation.created_by_user_id
                    ORDER BY receipt.created_at DESC, receipt.id DESC
                    LIMIT 1
                ) latest_receipt ON TRUE
                WHERE participation.plan_id = ?
                  AND participation.activity_id IS NOT DISTINCT FROM ?
                  AND participation.target_scope = ?
                  AND participation.workspace_id = ?
                  AND participation.created_by_user_id = ?
                """,
                CalendarParticipationService::row,
                planId,
                activityId,
                scope.type().name(),
                context.workspaceId(),
                context.actorId());
        return rows.stream().findFirst().map(CalendarParticipationService::view);
    }

    private ParticipationRow lockCurrent(
            CalendarParticipationAccess.Target target) {
        List<ParticipationRow> rows = jdbc.query(
                """
                SELECT id, plan_id, activity_id, target_scope,
                       participation_state, participation_revision,
                       operation_payload_hash, NULL AS disposition,
                       NULL AS acknowledgement_status
                FROM calendar_participation
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                FOR UPDATE
                """,
                CalendarParticipationService::row,
                target.planId(),
                target.activityId(),
                target.scopeType().name(),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        return rows.stream().findFirst().orElse(null);
    }

    private ParticipationRow findByRequest(
            String requestHash, WorkspaceContext context) {
        List<ParticipationRow> rows = jdbc.query(
                """
                SELECT participation.id, participation.plan_id,
                       participation.activity_id,
                       participation.target_scope,
                       history.current_state AS participation_state,
                       history.participation_revision,
                       history.operation_payload_hash,
                       NULL AS disposition,
                       NULL AS acknowledgement_status
                FROM calendar_participation_history history
                JOIN calendar_participation participation
                  ON participation.id = history.participation_id
                 AND participation.workspace_id = history.workspace_id
                 AND participation.created_by_user_id =
                     history.created_by_user_id
                WHERE history.workspace_id = ?
                  AND history.created_by_user_id = ?
                  AND history.operation_request_hash = ?
                """,
                CalendarParticipationService::row,
                context.workspaceId(),
                context.actorId(),
                requestHash);
        return rows.stream().findFirst().orElse(null);
    }

    private Policy currentPolicy(
            CalendarParticipationAccess.Target target) {
        List<Policy> policies = jdbc.query(
                """
                SELECT participation_policy, policy_revision
                FROM calendar_participation_policy
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                (row, ignored) -> new Policy(
                        CalendarParticipationPolicy.valueOf(
                                row.getString("participation_policy")),
                        row.getLong("policy_revision")),
                target.planId(),
                target.activityId(),
                target.scopeType().name(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        return policies.stream()
                .findFirst()
                .orElse(new Policy(CalendarParticipationPolicy.OPTIONAL, null));
    }

    private static ParticipationRow row(
            java.sql.ResultSet row, int ignored)
            throws java.sql.SQLException {
        return new ParticipationRow(
                row.getObject("id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getObject("activity_id", UUID.class),
                CalendarParticipationScopeType.valueOf(
                        row.getString("target_scope")),
                CalendarParticipationStatus.valueOf(
                        row.getString("participation_state")),
                row.getLong("participation_revision"),
                row.getString("operation_payload_hash"),
                row.getString("disposition"),
                row.getString("acknowledgement_status"));
    }

    private static CalendarParticipationView view(ParticipationRow row) {
        CalendarParticipationPropagationState propagation =
                propagation(row.status(), row.disposition());
        return new CalendarParticipationView(
                row.id(),
                row.planId(),
                new CalendarParticipationScope(
                        row.scopeType(),
                        row.activityId() == null
                                ? row.planId()
                                : row.activityId()),
                row.status(),
                row.revision(),
                propagation,
                propagation
                                == CalendarParticipationPropagationState
                                        .AUTO_APPLIED_AWAITING_ACKNOWLEDGEMENT
                        && !"ACKNOWLEDGED".equals(row.acknowledgementStatus()));
    }

    private static CalendarParticipationView view(
            CalendarParticipationRequestStore.Receipt receipt) {
        CalendarParticipationStatus status =
                CalendarParticipationStatus.valueOf(receipt.resultState());
        CalendarParticipationPropagationState propagation =
                propagation(status, null);
        return new CalendarParticipationView(
                receipt.participationId(),
                receipt.planId(),
                new CalendarParticipationScope(
                        receipt.activityId() == null
                                ? CalendarParticipationScopeType.PLAN
                                : CalendarParticipationScopeType.ACTIVITY,
                        receipt.activityId() == null
                                ? receipt.planId()
                                : receipt.activityId()),
                status,
                receipt.resultRevision(),
                propagation,
                false);
    }

    private static CalendarParticipationPropagationState propagation(
            CalendarParticipationStatus status, String disposition) {
        if (status == CalendarParticipationStatus.DECLINED
                || status == CalendarParticipationStatus.OPTED_OUT) {
            return CalendarParticipationPropagationState.SUPPRESSED;
        }
        if ("AUTO_APPLIED".equals(disposition)) {
            return CalendarParticipationPropagationState
                    .AUTO_APPLIED_AWAITING_ACKNOWLEDGEMENT;
        }
        if ("REVIEW_REQUIRED".equals(disposition)) {
            return CalendarParticipationPropagationState.REVIEW_REQUIRED;
        }
        return CalendarParticipationPropagationState.CURRENT;
    }

    private static UUID activityId(
            UUID planId, CalendarParticipationScope scope) {
        if (scope.type() == CalendarParticipationScopeType.OCCURRENCE) {
            throw new BusinessException(
                    "CALENDAR_OCCURRENCE_PARTICIPATION_NOT_YET_SUPPORTED",
                    "Occurrence participation is delivered in Wheel 10");
        }
        if (scope.type() == CalendarParticipationScopeType.PLAN) {
            if (!planId.equals(scope.targetId())) {
                throw new IllegalArgumentException(
                        "Plan participation scope must identify its plan");
            }
            return null;
        }
        return scope.targetId();
    }

    private static void requireChange(CalendarParticipationChange command) {
        if (command == null
                || command.planId() == null
                || command.scope() == null
                || command.status() == null
                || command.expectedRevision() < 0) {
            throw new IllegalArgumentException(
                    "A participation target, status, and non-negative revision are required");
        }
    }

    private static void requirePayload(
            String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The participation request key was used for another change");
        }
    }

    private static void requireConfirmedExit(
            ParticipationRow current,
            Policy policy,
            CalendarParticipationStatus requested) {
        boolean exiting = requested == CalendarParticipationStatus.DECLINED
                || requested == CalendarParticipationStatus.OPTED_OUT;
        boolean committed = current != null
                && current.status() == CalendarParticipationStatus.COMMITTED;
        if (exiting
                && (committed
                        || policy.policy()
                                == CalendarParticipationPolicy.REQUIRED)) {
            throw new BusinessException(
                    "CALENDAR_PARTICIPATION_EXIT_CONFIRMATION_REQUIRED",
                    "Committed or required participation must be skipped through a revision-bound impact confirmation");
        }
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "CALENDAR_PARTICIPATION_REVISION_CONFLICT",
                "Participation changed; reload before updating it");
    }

    private record Policy(
            CalendarParticipationPolicy policy, Long revision) {}

    private record ParticipationRow(
            UUID id,
            UUID planId,
            UUID activityId,
            CalendarParticipationScopeType scopeType,
            CalendarParticipationStatus status,
            long revision,
            String payloadHash,
            String disposition,
            String acknowledgementStatus) {}
}
