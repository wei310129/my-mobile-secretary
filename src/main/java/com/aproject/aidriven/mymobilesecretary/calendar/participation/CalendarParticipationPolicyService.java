package com.aproject.aidriven.mymobilesecretary.calendar.participation;

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
public class CalendarParticipationPolicyService {

    private final CalendarParticipationAccess access;
    private final CalendarParticipationRequestStore requests;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarParticipationPolicyService(
            CalendarParticipationAccess access,
            CalendarParticipationRequestStore requests,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.requests = requests;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarParticipationPolicyView change(
            CalendarParticipationPolicyChange command) {
        if (command == null
                || command.planId() == null
                || command.scope() == null
                || command.policy() == null
                || command.expectedRevision() < 0) {
            throw new IllegalArgumentException(
                    "A participation policy target, value, and revision are required");
        }
        CalendarParticipationAccess.Target target =
                access.lockVisibleTarget(command.planId(), command.scope());
        if (!target.context().actorId().equals(target.sourceOwnerId())) {
            throw new SecurityException(
                    "Only the calendar owner can change participation policy");
        }
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|"
                        + command.policy());
        String semanticHash = CalendarParticipationAccess.hash(
                "POLICY|"
                        + target.planId()
                        + "|"
                        + target.activityId()
                        + "|"
                        + command.policy());
        access.lock("participation-policy-request|" + requestHash);
        access.lock("participation-policy-target|"
                + target.context().workspaceId()
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
                    receipt, "POLICY_CHANGE", payloadHash);
            return view(receipt);
        }
        PolicyRow current = lockCurrent(target);
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
        if (current != null && current.policy() == command.policy()) {
            requests.recordPolicy(
                    requestHash,
                    payloadHash,
                    semanticHash,
                    current.id(),
                    target,
                    current.policy(),
                    current.revision());
            return view(current);
        }
        Instant now = Instant.now(clock);
        if (current == null) {
            jdbc.update(
                    """
                    INSERT INTO calendar_participation_policy (
                        id, plan_id, activity_id, target_scope,
                        participation_policy, policy_revision, version,
                        operation_request_hash, operation_payload_hash,
                        created_at, updated_at, workspace_id,
                        created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    target.planId(),
                    target.activityId(),
                    target.scopeType().name(),
                    command.policy().name(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.context().actorId());
        } else {
            int updated = jdbc.update(
                    """
                    UPDATE calendar_participation_policy
                    SET participation_policy = ?,
                        policy_revision = policy_revision + 1,
                        version = version + 1,
                        operation_request_hash = ?,
                        operation_payload_hash = ?, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                      AND policy_revision = ?
                    """,
                    command.policy().name(),
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
        CalendarParticipationPolicyView result =
                current(command.planId(), command.scope()).orElseThrow();
        requests.recordPolicy(
                requestHash,
                payloadHash,
                semanticHash,
                result.id(),
                target,
                result.policy(),
                result.revision());
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<CalendarParticipationPolicyView> current(
            UUID planId, CalendarParticipationScope scope) {
        CalendarParticipationAccess.Target target =
                access.lockVisibleTarget(planId, scope);
        List<PolicyRow> rows = jdbc.query(
                """
                SELECT id, plan_id, activity_id, target_scope,
                       participation_policy, policy_revision,
                       operation_request_hash, operation_payload_hash
                FROM calendar_participation_policy
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                CalendarParticipationPolicyService::row,
                target.planId(),
                target.activityId(),
                target.scopeType().name(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        return rows.stream().findFirst()
                .map(CalendarParticipationPolicyService::view);
    }

    private PolicyRow lockCurrent(
            CalendarParticipationAccess.Target target) {
        List<PolicyRow> rows = jdbc.query(
                """
                SELECT id, plan_id, activity_id, target_scope,
                       participation_policy, policy_revision,
                       operation_request_hash, operation_payload_hash
                FROM calendar_participation_policy
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                CalendarParticipationPolicyService::row,
                target.planId(),
                target.activityId(),
                target.scopeType().name(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        return rows.stream().findFirst().orElse(null);
    }

    private static PolicyRow row(
            java.sql.ResultSet row, int ignored)
            throws java.sql.SQLException {
        return new PolicyRow(
                row.getObject("id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getObject("activity_id", UUID.class),
                CalendarParticipationScopeType.valueOf(
                        row.getString("target_scope")),
                CalendarParticipationPolicy.valueOf(
                        row.getString("participation_policy")),
                row.getLong("policy_revision"),
                row.getString("operation_request_hash"),
                row.getString("operation_payload_hash"));
    }

    private static CalendarParticipationPolicyView view(PolicyRow row) {
        return new CalendarParticipationPolicyView(
                row.id(),
                row.planId(),
                new CalendarParticipationScope(
                        row.scopeType(),
                        row.activityId() == null
                                ? row.planId()
                                : row.activityId()),
                row.policy(),
                row.revision());
    }

    private static CalendarParticipationPolicyView view(
            CalendarParticipationRequestStore.Receipt receipt) {
        return new CalendarParticipationPolicyView(
                receipt.policyId(),
                receipt.planId(),
                new CalendarParticipationScope(
                        receipt.activityId() == null
                                ? CalendarParticipationScopeType.PLAN
                                : CalendarParticipationScopeType.ACTIVITY,
                        receipt.activityId() == null
                                ? receipt.planId()
                                : receipt.activityId()),
                CalendarParticipationPolicy.valueOf(receipt.resultState()),
                receipt.resultRevision());
    }

    private static void requirePayload(
            String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The policy request key was used for another change");
        }
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "CALENDAR_PARTICIPATION_POLICY_REVISION_CONFLICT",
                "Participation policy changed; reload before updating it");
    }

    private record PolicyRow(
            UUID id,
            UUID planId,
            UUID activityId,
            CalendarParticipationScopeType scopeType,
            CalendarParticipationPolicy policy,
            long revision,
            String requestHash,
            String payloadHash) {}
}
