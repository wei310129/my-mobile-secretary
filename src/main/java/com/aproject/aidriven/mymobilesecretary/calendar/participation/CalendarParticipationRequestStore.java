package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class CalendarParticipationRequestStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    CalendarParticipationRequestStore(
            JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Receipt find(String requestHash, WorkspaceContext context) {
        List<Receipt> rows = jdbc.query(
                """
                SELECT operation_kind, operation_payload_hash,
                       participation_id, routine_subscription_id,
                       participation_policy_id, skip_confirmation_id,
                       plan_id, activity_id, result_state,
                       result_revision
                FROM calendar_participation_request_receipt
                WHERE operation_request_hash = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                (row, ignored) -> new Receipt(
                        row.getString("operation_kind"),
                        row.getString("operation_payload_hash"),
                        row.getObject("participation_id", UUID.class),
                        row.getObject(
                                "routine_subscription_id", UUID.class),
                        row.getObject(
                                "participation_policy_id", UUID.class),
                        row.getObject(
                                "skip_confirmation_id", UUID.class),
                        row.getObject("plan_id", UUID.class),
                        row.getObject("activity_id", UUID.class),
                        row.getString("result_state"),
                        row.getLong("result_revision")),
                requestHash,
                context.workspaceId(),
                context.actorId());
        return rows.stream().findFirst().orElse(null);
    }

    void recordParticipation(
            String requestHash,
            String payloadHash,
            String semanticHash,
            UUID participationId,
            CalendarParticipationAccess.Target target,
            CalendarParticipationStatus status,
            long revision) {
        insert(
                "PARTICIPATION",
                requestHash,
                payloadHash,
                semanticHash,
                participationId,
                null,
                null,
                null,
                target,
                status.name(),
                revision);
    }

    void recordWatch(
            boolean subscribing,
            String requestHash,
            String payloadHash,
            String semanticHash,
            UUID subscriptionId,
            CalendarParticipationAccess.Target target,
            long revision) {
        insert(
                subscribing ? "WATCH_SUBSCRIBE" : "WATCH_UNSUBSCRIBE",
                requestHash,
                payloadHash,
                semanticHash,
                null,
                subscriptionId,
                null,
                null,
                target,
                subscribing ? "WATCHING" : "UNSUBSCRIBED",
                revision);
    }

    void recordPolicy(
            String requestHash,
            String payloadHash,
            String semanticHash,
            UUID policyId,
            CalendarParticipationAccess.Target target,
            CalendarParticipationPolicy policy,
            long revision) {
        insert(
                "POLICY_CHANGE",
                requestHash,
                payloadHash,
                semanticHash,
                null,
                null,
                policyId,
                null,
                target,
                policy.name(),
                revision);
    }

    private void insert(
            String kind,
            String requestHash,
            String payloadHash,
            String semanticHash,
            UUID participationId,
            UUID subscriptionId,
            UUID policyId,
            UUID skipConfirmationId,
            CalendarParticipationAccess.Target target,
            String resultState,
            long revision) {
        jdbc.update(
                """
                INSERT INTO calendar_participation_request_receipt (
                    id, operation_kind, operation_request_hash,
                    operation_payload_hash, semantic_identity_hash,
                    participation_id, routine_subscription_id,
                    participation_policy_id, skip_confirmation_id,
                    plan_id, activity_id, result_state, result_revision,
                    recorded_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?)
                """,
                UUID.randomUUID(),
                kind,
                requestHash,
                payloadHash,
                semanticHash,
                participationId,
                subscriptionId,
                policyId,
                skipConfirmationId,
                target.planId(),
                target.activityId(),
                resultState,
                revision,
                Timestamp.from(Instant.now(clock)),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    static void require(
            Receipt receipt, String kind, String payloadHash) {
        if (!kind.equals(receipt.kind())
                || !payloadHash.equals(receipt.payloadHash())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was used for another calendar participation operation");
        }
    }

    record Receipt(
            String kind,
            String payloadHash,
            UUID participationId,
            UUID subscriptionId,
            UUID policyId,
            UUID skipConfirmationId,
            UUID planId,
            UUID activityId,
            String resultState,
            long resultRevision) {}
}
