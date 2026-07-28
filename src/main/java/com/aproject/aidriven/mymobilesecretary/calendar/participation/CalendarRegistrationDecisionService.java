package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarRegistrationDecisionService {

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarRegistrationDecisionService(
            CalendarRegistrationAccess access,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarRegistrationDecisionView decide(
            CalendarRegistrationDecisionCommand command) {
        requireCommand(command);
        String reason = normalizeReason(command.reason());
        CalendarRegistrationAccess.Target target =
                access.lockTarget(command.planId(), command.scope());
        access.requireManager(target, true);
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|"
                        + command.registrationId()
                        + "|"
                        + command.decision()
                        + "|"
                        + reason
                        + "|"
                        + command.expectedRevision()
                        + "|"
                        + command.overrideConfirmed());
        access.lock("registration-decision-request|" + requestHash);
        Receipt replay = receipt(requestHash, target);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            Registration registration =
                    registration(replay.registrationId(), target, false);
            Bucket bucket = bucket(target, false);
            return view(registration, bucket, target, false);
        }

        Bucket bucket = bucket(target, true);
        Registration registration =
                registration(command.registrationId(), target, true);
        if (registration.revision() != command.expectedRevision()) {
            throw revisionConflict();
        }
        if (registration.state()
                != CalendarRegistrationState.APPROVAL_REQUIRED) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_NOT_AWAITING_APPROVAL",
                    "Only an approval-required registration may be decided");
        }
        if (!"TENTATIVE".equals(registration.participationState())) {
            throw new BusinessException(
                    "CALENDAR_PARTICIPATION_STATE_CONFLICT",
                    "Approval-required registration must remain tentative");
        }

        boolean approving = command.decision()
                == CalendarRegistrationDecisionCommand.Decision.APPROVE;
        boolean full = bucket.capacity() != null
                && bucket.committed() >= bucket.capacity();
        if (approving && full
                && bucket.limitMode()
                        == CalendarRegistrationLimitMode.HARD_LIMIT) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_FULL",
                    "Registration is full and hard-limit approval is forbidden");
        }
        if (approving && full
                && bucket.limitMode()
                        == CalendarRegistrationLimitMode.MANAGER_OVERRIDE
                && !command.overrideConfirmed()) {
            return view(registration, bucket, target, true);
        }

        Instant now = Instant.now(clock);
        CalendarRegistrationState resultState = approving
                ? CalendarRegistrationState.COMMITTED
                : CalendarRegistrationState.DECLINED;
        String participationState =
                approving ? "COMMITTED" : "DECLINED";
        long registrationRevision = registration.revision() + 1;
        updateParticipation(
                registration,
                participationState,
                requestHash,
                payloadHash,
                now,
                target);
        Bucket resultBucket = approving
                ? incrementCommitted(bucket, now, target)
                : bucket;
        updateRegistration(
                registration,
                resultState,
                registrationRevision,
                requestHash,
                payloadHash,
                now,
                target);
        insertRegistrationHistory(
                registration,
                resultState,
                registrationRevision,
                requestHash,
                payloadHash,
                reason,
                now,
                target);
        recordReceipt(
                registration,
                resultState,
                registrationRevision,
                requestHash,
                payloadHash,
                now,
                target);
        insertOutbox(
                registration,
                resultState,
                registrationRevision,
                resultBucket,
                requestHash,
                payloadHash,
                now,
                target);
        Registration updated =
                registration(registration.id(), target, false);
        return view(updated, resultBucket, target, false);
    }

    private void updateParticipation(
            Registration registration,
            String state,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        int changed = jdbc.update(
                """
                UPDATE calendar_participation
                SET participation_state = ?,
                    participation_revision =
                        participation_revision + 1,
                    operation_request_hash = ?,
                    operation_payload_hash = ?,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND participation_revision = ?
                  AND participation_state = 'TENTATIVE'
                """,
                state,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                registration.participationId(),
                target.context().workspaceId(),
                registration.actorId(),
                target.sourceOwnerId(),
                registration.participationRevision());
        if (changed != 1) {
            throw new BusinessException(
                    "CALENDAR_PARTICIPATION_CHANGED",
                    "Participation changed before the registration decision");
        }
        jdbc.update(
                """
                INSERT INTO calendar_participation_history (
                    id, participation_id, plan_id, activity_id,
                    target_scope, event_type, previous_state,
                    current_state, participation_revision,
                    source_revision, operation_request_hash,
                    operation_payload_hash, occurred_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (
                    ?, ?, ?, ?, ?, 'STATE_CHANGED', 'TENTATIVE',
                    ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                registration.participationId(),
                target.planId(),
                target.activityId(),
                target.scopeType().name(),
                state,
                registration.participationRevision() + 1,
                registration.sourceRevision(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                target.context().workspaceId(),
                registration.actorId(),
                target.sourceOwnerId());
    }

    private Bucket incrementCommitted(
            Bucket bucket,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        int committed = bucket.committed() + 1;
        int overCapacity = bucket.capacity() == null
                ? 0
                : Math.max(0, committed - bucket.capacity());
        int changed = jdbc.update(
                """
                UPDATE calendar_capacity_bucket
                SET committed_count = ?,
                    over_capacity_count = ?,
                    bucket_revision = bucket_revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND bucket_revision = ?
                """,
                committed,
                overCapacity,
                Timestamp.from(now),
                bucket.id(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                bucket.revision());
        if (changed != 1) {
            throw new BusinessException(
                    "CALENDAR_CAPACITY_CHANGED",
                    "Capacity changed before the registration decision");
        }
        return new Bucket(
                bucket.id(),
                bucket.capacity(),
                bucket.limitMode(),
                committed,
                overCapacity,
                bucket.revision() + 1);
    }

    private void updateRegistration(
            Registration registration,
            CalendarRegistrationState state,
            long revision,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        int changed = jdbc.update(
                """
                UPDATE calendar_registration
                SET registration_state = ?,
                    status_origin = 'MANAGER',
                    registration_revision = ?,
                    operation_request_hash = ?,
                    operation_payload_hash = ?,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND registration_revision = ?
                  AND registration_state = 'APPROVAL_REQUIRED'
                """,
                state.name(),
                revision,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                registration.id(),
                target.context().workspaceId(),
                registration.actorId(),
                target.sourceOwnerId(),
                registration.revision());
        if (changed != 1) {
            throw revisionConflict();
        }
    }

    private void insertRegistrationHistory(
            Registration registration,
            CalendarRegistrationState state,
            long revision,
            String requestHash,
            String payloadHash,
            String reason,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        String event = state == CalendarRegistrationState.COMMITTED
                ? "APPROVAL_APPROVED"
                : "APPROVAL_DECLINED";
        jdbc.update(
                """
                INSERT INTO calendar_registration_history (
                    id, registration_id, plan_id, activity_id,
                    event_type, previous_state, current_state,
                    status_origin, registration_revision,
                    operation_request_hash, operation_payload_hash,
                    reason_present, decision_reason,
                    occurred_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (
                    ?, ?, ?, ?, ?, 'APPROVAL_REQUIRED', ?,
                    'MANAGER', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                registration.id(),
                target.planId(),
                target.activityId(),
                event,
                state.name(),
                revision,
                requestHash,
                payloadHash,
                reason != null,
                reason,
                Timestamp.from(now),
                target.context().workspaceId(),
                registration.actorId(),
                target.sourceOwnerId());
    }

    private void recordReceipt(
            Registration registration,
            CalendarRegistrationState state,
            long revision,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        jdbc.update(
                """
                INSERT INTO calendar_registration_request_receipt (
                    id, request_kind, plan_id, activity_id,
                    registration_id, operation_request_hash,
                    operation_payload_hash, semantic_identity_hash,
                    result_state, result_revision, created_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (
                    ?, 'REGISTRATION_DECISION', ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                registration.id(),
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "DECISION|"
                                + registration.id()
                                + "|"
                                + revision
                                + "|"
                                + target.context().actorId()),
                state.name(),
                revision,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private void insertOutbox(
            Registration registration,
            CalendarRegistrationState state,
            long revision,
            Bucket bucket,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        String message = "event="
                + state
                + "; registration="
                + registration.id()
                + "; committed="
                + bucket.committed()
                + "; overCapacity="
                + bucket.overCapacity();
        jdbc.update(
                """
                INSERT INTO calendar_registration_outbox (
                    id, event_type, plan_id, activity_id,
                    registration_id, recipient_user_id,
                    operation_request_hash,
                    operation_payload_hash,
                    semantic_identity_hash, payload_text,
                    delivery_status, delivery_attempt_count,
                    next_delivery_attempt_at, delivered_at,
                    last_delivery_failure, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (
                    ?, 'REGISTRATION_RESULT', ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING', 0, ?, NULL, NULL, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                registration.id(),
                registration.actorId(),
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "REGISTRATION_RESULT|"
                                + registration.id()
                                + "|"
                                + revision
                                + "|"
                                + registration.actorId()),
                message,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private Receipt receipt(
            String requestHash,
            CalendarRegistrationAccess.Target target) {
        List<Receipt> rows = jdbc.query(
                """
                SELECT registration_id, operation_payload_hash
                FROM calendar_registration_request_receipt
                WHERE request_kind = 'REGISTRATION_DECISION'
                  AND operation_request_hash = ?
                  AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                (row, ignored) -> new Receipt(
                        row.getObject("registration_id", UUID.class),
                        row.getString("operation_payload_hash")),
                requestHash,
                target.context().workspaceId(),
                target.context().actorId());
        return rows.stream().findFirst().orElse(null);
    }

    private Bucket bucket(
            CalendarRegistrationAccess.Target target,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE OF bucket" : "";
        List<Bucket> rows = jdbc.query(
                """
                SELECT bucket.id, policy.capacity, policy.limit_mode,
                       bucket.committed_count,
                       bucket.over_capacity_count,
                       bucket.bucket_revision
                FROM calendar_registration_policy policy
                JOIN calendar_capacity_bucket bucket
                  ON bucket.policy_id = policy.id
                 AND bucket.plan_id = policy.plan_id
                 AND bucket.workspace_id = policy.workspace_id
                 AND bucket.source_created_by_user_id =
                        policy.source_created_by_user_id
                WHERE policy.plan_id = ?
                  AND policy.activity_id IS NOT DISTINCT FROM ?
                  AND policy.workspace_id = ?
                  AND policy.source_created_by_user_id = ?
                """
                        + suffix,
                CalendarRegistrationDecisionService::bucket,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        if (rows.size() != 1) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_POLICY_REQUIRED",
                    "This calendar target is not open for registration");
        }
        return rows.getFirst();
    }

    private Registration registration(
            UUID registrationId,
            CalendarRegistrationAccess.Target target,
            boolean lock) {
        String suffix = lock
                ? " FOR UPDATE OF registration, participation"
                : "";
        List<Registration> rows = jdbc.query(
                """
                SELECT registration.id, registration.participation_id,
                       registration.registration_state,
                       registration.registration_revision,
                       registration.created_by_user_id,
                       participation.participation_state,
                       participation.participation_revision,
                       participation.source_revision
                FROM calendar_registration registration
                JOIN calendar_participation participation
                  ON participation.id = registration.participation_id
                 AND participation.plan_id = registration.plan_id
                 AND participation.workspace_id =
                        registration.workspace_id
                 AND participation.created_by_user_id =
                        registration.created_by_user_id
                 AND participation.source_created_by_user_id =
                        registration.source_created_by_user_id
                WHERE registration.id = ?
                  AND registration.plan_id = ?
                  AND registration.activity_id IS NOT DISTINCT FROM ?
                  AND registration.workspace_id = ?
                  AND registration.source_created_by_user_id = ?
                """
                        + suffix,
                CalendarRegistrationDecisionService::registration,
                registrationId,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar registration", "requested registration");
        }
        return rows.getFirst();
    }

    private static Bucket bucket(ResultSet row, int ignored)
            throws SQLException {
        Number capacity = (Number) row.getObject("capacity");
        return new Bucket(
                row.getObject("id", UUID.class),
                capacity == null ? null : capacity.intValue(),
                CalendarRegistrationLimitMode.valueOf(
                        row.getString("limit_mode")),
                row.getInt("committed_count"),
                row.getInt("over_capacity_count"),
                row.getLong("bucket_revision"));
    }

    private static Registration registration(
            ResultSet row, int ignored) throws SQLException {
        return new Registration(
                row.getObject("id", UUID.class),
                row.getObject("participation_id", UUID.class),
                CalendarRegistrationState.valueOf(
                        row.getString("registration_state")),
                row.getLong("registration_revision"),
                row.getObject("created_by_user_id", UUID.class),
                row.getString("participation_state"),
                row.getLong("participation_revision"),
                row.getLong("source_revision"));
    }

    private static CalendarRegistrationDecisionView view(
            Registration registration,
            Bucket bucket,
            CalendarRegistrationAccess.Target target,
            boolean overrideConfirmationRequired) {
        return new CalendarRegistrationDecisionView(
                registration.id(),
                target.planId(),
                target.scope(),
                registration.state(),
                registration.revision(),
                overrideConfirmationRequired,
                overrideConfirmationRequired
                        ? "CAPACITY_OVERRIDE_CONFIRMATION_REQUIRED"
                        : null,
                bucket.committed(),
                bucket.overCapacity());
    }

    private static void requireCommand(
            CalendarRegistrationDecisionCommand command) {
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.planId() == null
                || command.scope() == null
                || command.registrationId() == null
                || command.decision() == null
                || command.expectedRevision() < 1
                || command.reason() != null
                        && command.reason().strip().length() > 500
                || (command.decision()
                                        == CalendarRegistrationDecisionCommand
                                                .Decision.REJECT
                                || command.overrideConfirmed())
                        && (command.reason() == null
                                || command.reason().isBlank())
                || command.decision()
                                == CalendarRegistrationDecisionCommand.Decision.REJECT
                        && command.overrideConfirmed()) {
            throw new IllegalArgumentException(
                    "A valid revision-bound registration decision is required");
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank()
                ? null
                : reason.strip();
    }

    private static void requirePayload(
            String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The registration decision request key was reused");
        }
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "CALENDAR_REGISTRATION_REVISION_CONFLICT",
                "Registration changed; reload before deciding it");
    }

    private record Receipt(UUID registrationId, String payloadHash) {}

    private record Registration(
            UUID id,
            UUID participationId,
            CalendarRegistrationState state,
            long revision,
            UUID actorId,
            String participationState,
            long participationRevision,
            long sourceRevision) {}

    private record Bucket(
            UUID id,
            Integer capacity,
            CalendarRegistrationLimitMode limitMode,
            int committed,
            int overCapacity,
            long revision) {}
}
