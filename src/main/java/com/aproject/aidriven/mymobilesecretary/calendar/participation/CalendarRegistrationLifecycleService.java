package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarRegistrationLifecycleService {

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarRegistrationLifecycleService(
            CalendarRegistrationAccess access,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarRegistrationView withdraw(
            CalendarRegistrationWithdrawalCommand command) {
        requireWithdrawal(command);
        CalendarRegistrationAccess.Target target =
                access.lockTarget(command.planId(), command.scope());
        String reason = normalize(command.reason());
        return exit(
                command.requestId(),
                target,
                target.context().actorId(),
                command.expectedRevision(),
                CalendarRegistrationState.WITHDRAWN_BY_USER,
                reason,
                command.shareReason(),
                "USER");
    }

    public CalendarRegistrationView remove(
            CalendarOrganizerRemovalCommand command) {
        requireRemoval(command);
        CalendarRegistrationAccess.Target target =
                access.lockTarget(command.planId(), command.scope());
        access.requireManager(target, true);
        return exit(
                command.requestId(),
                target,
                command.participantUserId(),
                command.expectedRevision(),
                CalendarRegistrationState.REMOVED_BY_ORGANIZER,
                normalize(command.reason()),
                false,
                "MANAGER");
    }

    private CalendarRegistrationView exit(
            String requestId,
            CalendarRegistrationAccess.Target target,
            UUID participantUserId,
            long expectedRevision,
            CalendarRegistrationState resultState,
            String reason,
            boolean shareReason,
            String origin) {
        String requestHash = CalendarParticipationAccess.hash(requestId);
        String payloadHash = CalendarParticipationAccess.hash(
                target.planId()
                        + "|"
                        + target.activityId()
                        + "|"
                        + participantUserId
                        + "|"
                        + expectedRevision
                        + "|"
                        + resultState
                        + "|"
                        + reason
                        + "|"
                        + shareReason);
        access.lock("registration-exit-request|" + requestHash);
        Receipt replay = receipt(requestHash, target);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            return view(
                    registration(replay.registrationId(), target, false),
                    target);
        }

        Bucket bucket = lockBucket(target);
        Registration registration =
                lockRegistration(participantUserId, target);
        if (registration.revision() != expectedRevision) {
            throw revisionConflict();
        }
        if (!isActive(registration.state())) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_NOT_ACTIVE",
                    "Only an active registration may exit");
        }
        Instant now = Instant.now(clock);
        closeWaitlist(
                registration,
                resultState == CalendarRegistrationState.WITHDRAWN_BY_USER
                        ? "WITHDRAWN"
                        : "REMOVED",
                requestHash,
                payloadHash,
                now,
                target);
        updateParticipation(
                registration,
                requestHash,
                payloadHash,
                now,
                target);
        Counts counts =
                updateBucket(bucket, registration.state(), now, target);
        long revision = registration.revision() + 1;
        updateRegistration(
                registration,
                resultState,
                reason,
                shareReason,
                origin,
                revision,
                requestHash,
                payloadHash,
                now,
                target);
        insertRegistrationHistory(
                registration,
                resultState,
                origin,
                revision,
                reason != null,
                requestHash,
                payloadHash,
                now,
                target);
        insertOutbox(
                registration,
                resultState,
                counts,
                bucket,
                requestHash,
                payloadHash,
                now,
                target);
        recordReceipt(
                registration.id(),
                resultState,
                revision,
                requestHash,
                payloadHash,
                now,
                target);
        return new CalendarRegistrationView(
                registration.id(),
                registration.planId(),
                target.scope(),
                resultState,
                revision,
                null);
    }

    private void closeWaitlist(
            Registration registration,
            String state,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        if (registration.state() != CalendarRegistrationState.WAITLISTED) {
            return;
        }
        jdbc.update(
                """
                UPDATE calendar_waitlist_offer
                SET offer_status = 'CANCELED',
                    offer_revision = offer_revision + 1,
                    responded_at = ?, updated_at = ?
                WHERE registration_id = ?
                  AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND offer_status = 'OFFERED'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                registration.id(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        int changed = jdbc.update(
                """
                UPDATE calendar_waitlist_entry
                SET entry_state = ?, entry_revision = entry_revision + 1,
                    operation_request_hash = ?,
                    operation_payload_hash = ?,
                    updated_at = ?, exited_at = ?
                WHERE registration_id = ?
                  AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND entry_state IN ('WAITING', 'OFFERED')
                """,
                state,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                registration.id(),
                target.context().workspaceId(),
                registration.actorId(),
                target.sourceOwnerId());
        if (changed != 1) {
            throw new BusinessException(
                    "CALENDAR_WAITLIST_CHANGED",
                    "Waitlist state changed before registration exit");
        }
    }

    private void updateParticipation(
            Registration registration,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        if (!"OPTED_OUT".equals(registration.participationState())) {
            int changed = jdbc.update(
                    """
                    UPDATE calendar_participation
                    SET participation_state = 'OPTED_OUT',
                        participation_revision =
                            participation_revision + 1,
                        operation_request_hash = ?,
                        operation_payload_hash = ?,
                        updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                      AND source_created_by_user_id = ?
                      AND participation_revision = ?
                    """,
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
                        "Participation changed before registration exit");
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
                        ?, ?, ?, ?, ?, 'STATE_CHANGED', ?,
                        'OPTED_OUT', ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    registration.participationId(),
                    target.planId(),
                    target.activityId(),
                    target.scopeType().name(),
                    registration.participationState(),
                    registration.participationRevision() + 1,
                    registration.sourceRevision(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    registration.actorId(),
                    target.sourceOwnerId());
        }
    }

    private Counts updateBucket(
            Bucket bucket,
            CalendarRegistrationState previousState,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        int committedAfter = bucket.committed()
                - (previousState == CalendarRegistrationState.COMMITTED ? 1 : 0);
        int waitlistedAfter = bucket.waitlisted()
                - (previousState == CalendarRegistrationState.WAITLISTED ? 1 : 0);
        int overAfter = bucket.capacity() == null
                ? 0
                : Math.max(0, committedAfter - bucket.capacity());
        int changed = jdbc.update(
                """
                UPDATE calendar_capacity_bucket
                SET committed_count = ?, waitlisted_count = ?,
                    over_capacity_count = ?,
                    bucket_revision = bucket_revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND bucket_revision = ?
                """,
                committedAfter,
                waitlistedAfter,
                overAfter,
                Timestamp.from(now),
                bucket.id(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                bucket.revision());
        if (changed != 1) {
            throw new BusinessException(
                    "CALENDAR_CAPACITY_CHANGED",
                    "Capacity changed before registration exit");
        }
        return new Counts(
                bucket.committed(),
                committedAfter,
                bucket.waitlisted(),
                waitlistedAfter,
                bucket.overCapacity(),
                overAfter);
    }

    private void updateRegistration(
            Registration registration,
            CalendarRegistrationState state,
            String reason,
            boolean shareReason,
            String origin,
            long revision,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        boolean withdrawal =
                state == CalendarRegistrationState.WITHDRAWN_BY_USER;
        int changed = jdbc.update(
                """
                UPDATE calendar_registration
                SET registration_state = ?, status_origin = ?,
                    registration_revision = ?,
                    withdrawal_reason = ?,
                    withdrawal_reason_shared = ?,
                    removed_by_user_id = ?,
                    removal_reason = ?,
                    operation_request_hash = ?,
                    operation_payload_hash = ?,
                    updated_at = ?, withdrawn_at = ?, removed_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND registration_revision = ?
                """,
                state.name(),
                origin,
                revision,
                withdrawal ? reason : null,
                withdrawal && shareReason,
                withdrawal ? null : target.context().actorId(),
                withdrawal ? null : reason,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                withdrawal ? Timestamp.from(now) : null,
                withdrawal ? null : Timestamp.from(now),
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
            String origin,
            long revision,
            boolean reasonPresent,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        jdbc.update(
                """
                INSERT INTO calendar_registration_history (
                    id, registration_id, plan_id, activity_id,
                    event_type, previous_state, current_state,
                    status_origin, registration_revision,
                    operation_request_hash, operation_payload_hash,
                    reason_present, occurred_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                registration.id(),
                target.planId(),
                target.activityId(),
                state.name(),
                registration.state().name(),
                state.name(),
                origin,
                revision,
                requestHash,
                payloadHash,
                reasonPresent,
                Timestamp.from(now),
                target.context().workspaceId(),
                registration.actorId(),
                target.sourceOwnerId());
    }

    private void insertOutbox(
            Registration registration,
            CalendarRegistrationState state,
            Counts counts,
            Bucket bucket,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(registration.actorId());
        boolean late = bucket.closesAt() != null
                && !now.isBefore(bucket.closesAt());
        if (late
                && bucket.notificationPolicy()
                        == CalendarLateNotificationPolicy.IMMEDIATE) {
            recipients.addAll(jdbc.queryForList(
                    """
                    SELECT manager_user_id
                    FROM calendar_organizer_assignment
                    WHERE plan_id = ?
                      AND activity_id IS NOT DISTINCT FROM ?
                      AND workspace_id = ?
                      AND source_created_by_user_id = ?
                      AND assignment_status = 'ACTIVE'
                      AND notification_recipient
                    ORDER BY manager_user_id
                    """,
                    UUID.class,
                    target.planId(),
                    target.activityId(),
                    target.context().workspaceId(),
                    target.sourceOwnerId()));
        }
        String displayName = jdbc.queryForObject(
                "SELECT display_name FROM app_user WHERE id = ?",
                String.class,
                registration.actorId());
        int remainingBefore = remaining(bucket.capacity(), counts.committedBefore());
        int remainingAfter = remaining(bucket.capacity(), counts.committedAfter());
        String message = "event="
                + state
                + "; participant="
                + displayName
                + "; committed="
                + counts.committedBefore()
                + "->"
                + counts.committedAfter()
                + "; waitlisted="
                + counts.waitlistedBefore()
                + "->"
                + counts.waitlistedAfter()
                + "; remaining="
                + remainingBefore
                + "->"
                + remainingAfter
                + "; overCapacity="
                + counts.overBefore()
                + "->"
                + counts.overAfter()
                + "; waitlistResult=NONE";
        for (UUID recipient : recipients) {
            String semanticHash = CalendarParticipationAccess.hash(
                    state
                            + "|"
                            + registration.id()
                            + "|"
                            + registration.revision()
                            + "|"
                            + recipient);
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
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'PENDING', 0, ?, NULL, NULL, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    UUID.randomUUID(),
                    state.name(),
                    target.planId(),
                    target.activityId(),
                    registration.id(),
                    recipient,
                    requestHash,
                    payloadHash,
                    semanticHash,
                    message,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    target.sourceOwnerId());
        }
    }

    private void recordReceipt(
            UUID registrationId,
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
                    ?, 'REGISTRATION_CHANGE', ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                registrationId,
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        state + "|" + registrationId + "|" + revision),
                state.name(),
                revision,
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
                WHERE request_kind = 'REGISTRATION_CHANGE'
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

    private Bucket lockBucket(
            CalendarRegistrationAccess.Target target) {
        List<Bucket> rows = jdbc.query(
                """
                SELECT bucket.id, policy.capacity, policy.closes_at,
                       policy.late_notification_policy,
                       bucket.committed_count,
                       bucket.waitlisted_count,
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
                FOR UPDATE OF bucket
                """,
                (row, ignored) -> {
                    Number capacity = (Number) row.getObject("capacity");
                    Timestamp closes = row.getTimestamp("closes_at");
                    return new Bucket(
                            row.getObject("id", UUID.class),
                            capacity == null ? null : capacity.intValue(),
                            closes == null ? null : closes.toInstant(),
                            CalendarLateNotificationPolicy.valueOf(
                                    row.getString("late_notification_policy")),
                            row.getInt("committed_count"),
                            row.getInt("waitlisted_count"),
                            row.getInt("over_capacity_count"),
                            row.getLong("bucket_revision"));
                },
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

    private Registration lockRegistration(
            UUID actorId,
            CalendarRegistrationAccess.Target target) {
        List<Registration> rows =
                registrationRows(actorId, target, true, null);
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar registration", "active participant");
        }
        return rows.getFirst();
    }

    private Registration registration(
            UUID registrationId,
            CalendarRegistrationAccess.Target target,
            boolean lock) {
        List<Registration> rows =
                registrationRows(null, target, lock, registrationId);
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar registration", "requested registration");
        }
        return rows.getFirst();
    }

    private List<Registration> registrationRows(
            UUID actorId,
            CalendarRegistrationAccess.Target target,
            boolean lock,
            UUID registrationId) {
        StringBuilder sql = new StringBuilder(
                """
                SELECT registration.id, registration.participation_id,
                       registration.plan_id,
                       registration.registration_state,
                       registration.registration_revision,
                       registration.created_by_user_id,
                       participation.participation_state,
                       participation.participation_revision,
                       participation.source_revision,
                       waitlist.queue_sequence
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
                LEFT JOIN calendar_waitlist_entry waitlist
                  ON waitlist.registration_id = registration.id
                 AND waitlist.workspace_id =
                        registration.workspace_id
                 AND waitlist.created_by_user_id =
                        registration.created_by_user_id
                 AND waitlist.entry_state IN ('WAITING', 'OFFERED')
                WHERE registration.plan_id = ?
                  AND registration.activity_id IS NOT DISTINCT FROM ?
                  AND registration.workspace_id = ?
                  AND registration.source_created_by_user_id = ?
                """);
        if (actorId != null) {
            sql.append(" AND registration.created_by_user_id = ?");
        }
        if (registrationId != null) {
            sql.append(" AND registration.id = ?");
        }
        if (lock) {
            sql.append(
                    " FOR UPDATE OF registration, participation");
        }
        java.util.ArrayList<Object> arguments =
                new java.util.ArrayList<>();
        arguments.add(target.planId());
        arguments.add(target.activityId());
        arguments.add(target.context().workspaceId());
        arguments.add(target.sourceOwnerId());
        if (actorId != null) {
            arguments.add(actorId);
        }
        if (registrationId != null) {
            arguments.add(registrationId);
        }
        return jdbc.query(
                sql.toString(),
                (row, ignored) -> {
                    Number sequence =
                            (Number) row.getObject("queue_sequence");
                    return new Registration(
                            row.getObject("id", UUID.class),
                            row.getObject("participation_id", UUID.class),
                            row.getObject("plan_id", UUID.class),
                            CalendarRegistrationState.valueOf(
                                    row.getString("registration_state")),
                            row.getLong("registration_revision"),
                            row.getObject(
                                    "created_by_user_id", UUID.class),
                            row.getString("participation_state"),
                            row.getLong("participation_revision"),
                            row.getLong("source_revision"),
                            sequence == null ? null : sequence.longValue());
                },
                arguments.toArray());
    }

    private static CalendarRegistrationView view(
            Registration row,
            CalendarRegistrationAccess.Target target) {
        return new CalendarRegistrationView(
                row.id(),
                row.planId(),
                target.scope(),
                row.state(),
                row.revision(),
                row.queueSequence());
    }

    private static boolean isActive(
            CalendarRegistrationState state) {
        return state == CalendarRegistrationState.COMMITTED
                || state == CalendarRegistrationState.WAITLISTED
                || state == CalendarRegistrationState.APPROVAL_REQUIRED;
    }

    private static int remaining(
            Integer capacity, int committed) {
        return capacity == null
                ? Integer.MAX_VALUE
                : Math.max(0, capacity - committed);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void requireWithdrawal(
            CalendarRegistrationWithdrawalCommand command) {
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.planId() == null
                || command.scope() == null
                || command.expectedRevision() < 1
                || normalize(command.reason()) != null
                        && normalize(command.reason()).length() > 500
                || command.shareReason()
                        && normalize(command.reason()) == null) {
            throw new IllegalArgumentException(
                    "A valid revision-bound withdrawal is required");
        }
    }

    private static void requireRemoval(
            CalendarOrganizerRemovalCommand command) {
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.planId() == null
                || command.scope() == null
                || command.participantUserId() == null
                || command.expectedRevision() < 1
                || normalize(command.reason()) == null
                || normalize(command.reason()).length() > 500) {
            throw new IllegalArgumentException(
                    "A manager removal requires participant, revision, and reason");
        }
    }

    private static void requirePayload(
            String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The registration exit request key was reused");
        }
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "CALENDAR_REGISTRATION_REVISION_CONFLICT",
                "Registration changed; reload before exiting");
    }

    private record Receipt(
            UUID registrationId, String payloadHash) {}

    private record Registration(
            UUID id,
            UUID participationId,
            UUID planId,
            CalendarRegistrationState state,
            long revision,
            UUID actorId,
            String participationState,
            long participationRevision,
            long sourceRevision,
            Long queueSequence) {}

    private record Bucket(
            UUID id,
            Integer capacity,
            Instant closesAt,
            CalendarLateNotificationPolicy notificationPolicy,
            int committed,
            int waitlisted,
            int overCapacity,
            long revision) {}

    private record Counts(
            int committedBefore,
            int committedAfter,
            int waitlistedBefore,
            int waitlistedAfter,
            int overBefore,
            int overAfter) {}
}
