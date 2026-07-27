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
public class CalendarRegistrationService {

    private final CalendarRegistrationAccess registrationAccess;
    private final CalendarParticipationAccess participationAccess;
    private final CalendarParticipationService participations;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarRegistrationService(
            CalendarRegistrationAccess registrationAccess,
            CalendarParticipationAccess participationAccess,
            CalendarParticipationService participations,
            JdbcTemplate jdbc,
            Clock clock) {
        this.registrationAccess = registrationAccess;
        this.participationAccess = participationAccess;
        this.participations = participations;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarRegistrationView join(
            CalendarRegistrationJoinCommand command) {
        requireCommand(command);
        CalendarParticipationAccess.Target participationTarget =
                participationAccess.lockVisibleTarget(
                        command.planId(), command.scope());
        CalendarRegistrationAccess.Target target =
                registrationAccess.lockTarget(
                        command.planId(), command.scope());
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|JOIN");
        registrationAccess.lock(
                "registration-request|" + requestHash);
        RegistrationRow receiptReplay =
                registrationByReceipt(requestHash, target);
        if (receiptReplay != null) {
            requirePayload(
                    receiptReplay.payloadHash(), payloadHash);
            return view(receiptReplay, target);
        }
        Bucket bucket = lockBucket(target);
        RegistrationRow current = lockCurrent(target);
        if (current != null
                && (current.state()
                                == CalendarRegistrationState.COMMITTED
                        || current.state()
                                == CalendarRegistrationState.WAITLISTED
                        || current.state()
                                == CalendarRegistrationState
                                        .APPROVAL_REQUIRED)) {
            recordReceipt(
                    requestHash,
                    payloadHash,
                    current,
                    target,
                    Instant.now(clock));
            return view(current, target);
        }
        Instant now = Instant.now(clock);
        CalendarRegistrationState next =
                decide(bucket, now);
        CalendarParticipationStatus participationStatus =
                next == CalendarRegistrationState.COMMITTED
                        ? CalendarParticipationStatus.COMMITTED
                        : CalendarParticipationStatus.TENTATIVE;
        Optional<CalendarParticipationView> existingParticipation =
                participations.current(
                        command.planId(), command.scope());
        CalendarParticipationView participation =
                participations.change(new CalendarParticipationChange(
                        command.requestId() + "|participation",
                        command.planId(),
                        command.scope(),
                        participationStatus,
                        existingParticipation
                                .map(CalendarParticipationView::revision)
                                .orElse(0L)));
        UUID registrationId =
                current == null ? UUID.randomUUID() : current.id();
        long revision = current == null ? 1 : current.revision() + 1;
        if (current == null) {
            jdbc.update(
                    """
                    INSERT INTO calendar_registration (
                        id, policy_id, participation_id, plan_id,
                        activity_id, target_scope,
                        registration_state, status_origin,
                        applied_policy_revision,
                        registration_revision,
                        withdrawal_reason,
                        withdrawal_reason_shared,
                        removed_by_user_id, removal_reason,
                        operation_request_hash,
                        operation_payload_hash, joined_at,
                        updated_at, withdrawn_at, removed_at,
                        workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, 'USER', ?, 1,
                        NULL, FALSE, NULL, NULL, ?, ?, ?, ?,
                        NULL, NULL, ?, ?, ?)
                    """,
                    registrationId,
                    bucket.policyId(),
                    participation.id(),
                    target.planId(),
                    target.activityId(),
                    target.scopeType().name(),
                    next.name(),
                    bucket.policyRevision(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    target.sourceOwnerId());
        } else {
            jdbc.update(
                    """
                    UPDATE calendar_registration
                    SET participation_id = ?,
                        registration_state = ?,
                        status_origin = 'USER',
                        applied_policy_revision = ?,
                        registration_revision =
                            registration_revision + 1,
                        withdrawal_reason = NULL,
                        withdrawal_reason_shared = FALSE,
                        removed_by_user_id = NULL,
                        removal_reason = NULL,
                        operation_request_hash = ?,
                        operation_payload_hash = ?,
                        updated_at = ?, withdrawn_at = NULL,
                        removed_at = NULL
                    WHERE id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                      AND registration_revision = ?
                    """,
                    participation.id(),
                    next.name(),
                    bucket.policyRevision(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    current.id(),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    current.revision());
        }
        Long queueSequence = null;
        if (next == CalendarRegistrationState.WAITLISTED) {
            queueSequence = bucket.nextQueueSequence();
            jdbc.update(
                    """
                    INSERT INTO calendar_waitlist_entry (
                        id, policy_id, registration_id, plan_id,
                        activity_id, queue_sequence, entry_state,
                        entry_revision, operation_request_hash,
                        operation_payload_hash, queued_at,
                        updated_at, exited_at, workspace_id,
                        created_by_user_id, source_created_by_user_id)
                    VALUES (
                        ?, ?, ?, ?, ?, ?, 'WAITING', 1,
                        ?, ?, ?, ?, NULL, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    bucket.policyId(),
                    registrationId,
                    target.planId(),
                    target.activityId(),
                    queueSequence,
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    target.sourceOwnerId());
        }
        updateBucketAfterJoin(
                bucket,
                next,
                queueSequence != null,
                now,
                target);
        insertHistory(
                registrationId,
                next,
                revision,
                requestHash,
                payloadHash,
                now,
                target);
        RegistrationRow inserted =
                lockCurrent(target);
        recordReceipt(
                requestHash, payloadHash, inserted, target, now);
        return view(inserted, target);
    }

    @Transactional(readOnly = true)
    public Optional<CalendarRegistrationView> current(
            UUID planId, CalendarParticipationScope scope) {
        CalendarRegistrationAccess.Target target =
                registrationAccess.lockTarget(planId, scope);
        return Optional.ofNullable(currentRow(target))
                .map(row -> view(row, target));
    }

    private CalendarRegistrationState decide(
            Bucket bucket, Instant now) {
        if (bucket.opensAt() != null
                && now.isBefore(bucket.opensAt())) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_NOT_OPEN",
                    "Registration has not opened yet");
        }
        boolean late = bucket.closesAt() != null
                && !now.isBefore(bucket.closesAt());
        if (late) {
            return switch (bucket.lateJoinPolicy()) {
                case CLOSED -> throw new BusinessException(
                        "CALENDAR_REGISTRATION_CLOSED",
                        "Registration is closed");
                case WAITLIST_ONLY -> requireWaitlist(bucket);
                case REQUIRE_APPROVAL ->
                    CalendarRegistrationState.APPROVAL_REQUIRED;
                case ALLOW_IF_CAPACITY -> capacityDecision(bucket);
            };
        }
        return capacityDecision(bucket);
    }

    private CalendarRegistrationState capacityDecision(
            Bucket bucket) {
        if (bucket.capacity() == null
                || bucket.committed() < bucket.capacity()) {
            return CalendarRegistrationState.COMMITTED;
        }
        return requireWaitlist(bucket);
    }

    private static CalendarRegistrationState requireWaitlist(
            Bucket bucket) {
        if (!bucket.waitlistEnabled()) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_FULL",
                    "Registration is full and no waitlist is enabled");
        }
        return CalendarRegistrationState.WAITLISTED;
    }

    private Bucket lockBucket(
            CalendarRegistrationAccess.Target target) {
        List<Bucket> rows = jdbc.query(
                """
                SELECT bucket.id, bucket.policy_id,
                       policy.opens_at, policy.closes_at,
                       policy.capacity, policy.late_join_policy,
                       policy.waitlist_enabled,
                       policy.policy_revision,
                       bucket.committed_count,
                       bucket.waitlisted_count,
                       bucket.over_capacity_count,
                       bucket.next_queue_sequence,
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
                    Timestamp opens = row.getTimestamp("opens_at");
                    Timestamp closes = row.getTimestamp("closes_at");
                    Number capacity =
                            (Number) row.getObject("capacity");
                    return new Bucket(
                            row.getObject("id", UUID.class),
                            row.getObject("policy_id", UUID.class),
                            opens == null
                                    ? null
                                    : opens.toInstant(),
                            closes == null
                                    ? null
                                    : closes.toInstant(),
                            capacity == null
                                    ? null
                                    : capacity.intValue(),
                            CalendarLateJoinPolicy.valueOf(
                                    row.getString(
                                            "late_join_policy")),
                            row.getBoolean("waitlist_enabled"),
                            row.getLong("policy_revision"),
                            row.getInt("committed_count"),
                            row.getInt("waitlisted_count"),
                            row.getInt("over_capacity_count"),
                            row.getLong("next_queue_sequence"),
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

    private void updateBucketAfterJoin(
            Bucket bucket,
            CalendarRegistrationState state,
            boolean allocatedSequence,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        int committedDelta =
                state == CalendarRegistrationState.COMMITTED ? 1 : 0;
        int waitlistedDelta =
                state == CalendarRegistrationState.WAITLISTED ? 1 : 0;
        int changed = jdbc.update(
                """
                UPDATE calendar_capacity_bucket
                SET committed_count = committed_count + ?,
                    waitlisted_count = waitlisted_count + ?,
                    next_queue_sequence =
                        next_queue_sequence + ?,
                    bucket_revision = bucket_revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND bucket_revision = ?
                """,
                committedDelta,
                waitlistedDelta,
                allocatedSequence ? 1 : 0,
                Timestamp.from(now),
                bucket.id(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                bucket.revision());
        if (changed != 1) {
            throw new BusinessException(
                    "CALENDAR_CAPACITY_CHANGED",
                    "Calendar capacity changed; retry registration");
        }
    }

    private RegistrationRow registrationByReceipt(
            String requestHash,
            CalendarRegistrationAccess.Target target) {
        List<UUID> ids = jdbc.queryForList(
                """
                SELECT registration_id
                FROM calendar_registration_request_receipt
                WHERE request_kind = 'REGISTRATION_CHANGE'
                  AND operation_request_hash = ?
                  AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                UUID.class,
                requestHash,
                target.context().workspaceId(),
                target.context().actorId());
        if (ids.isEmpty() || ids.getFirst() == null) {
            return null;
        }
        return rowById(ids.getFirst(), target);
    }

    private RegistrationRow lockCurrent(
            CalendarRegistrationAccess.Target target) {
        return currentRows(target, " FOR UPDATE OF registration").stream()
                .findFirst()
                .orElse(null);
    }

    private RegistrationRow currentRow(
            CalendarRegistrationAccess.Target target) {
        return currentRows(target, "").stream()
                .findFirst()
                .orElse(null);
    }

    private List<RegistrationRow> currentRows(
            CalendarRegistrationAccess.Target target,
            String suffix) {
        return jdbc.query(
                registrationSelect() + suffix,
                CalendarRegistrationService::row,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private RegistrationRow rowById(
            UUID id, CalendarRegistrationAccess.Target target) {
        List<RegistrationRow> rows = jdbc.query(
                registrationSelect()
                        + """
                         AND registration.id = ?
                        """,
                CalendarRegistrationService::row,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId(),
                id);
        return rows.stream().findFirst().orElse(null);
    }

    private static String registrationSelect() {
        return """
                SELECT registration.id, registration.plan_id,
                       registration.activity_id,
                       registration.target_scope,
                       registration.registration_state,
                       registration.registration_revision,
                       registration.operation_payload_hash,
                       waitlist.queue_sequence
                FROM calendar_registration registration
                LEFT JOIN calendar_waitlist_entry waitlist
                  ON waitlist.registration_id = registration.id
                 AND waitlist.workspace_id =
                        registration.workspace_id
                 AND waitlist.created_by_user_id =
                        registration.created_by_user_id
                 AND waitlist.entry_state IN (
                        'WAITING', 'OFFERED')
                WHERE registration.plan_id = ?
                  AND registration.activity_id IS NOT DISTINCT FROM ?
                  AND registration.workspace_id = ?
                  AND registration.created_by_user_id = ?
                  AND registration.source_created_by_user_id = ?
                """;
    }

    private static RegistrationRow row(
            java.sql.ResultSet result, int ignored)
            throws java.sql.SQLException {
        Number sequence =
                (Number) result.getObject("queue_sequence");
        return new RegistrationRow(
                result.getObject("id", UUID.class),
                result.getObject("plan_id", UUID.class),
                result.getObject("activity_id", UUID.class),
                result.getString("target_scope"),
                CalendarRegistrationState.valueOf(
                        result.getString("registration_state")),
                result.getLong("registration_revision"),
                result.getString("operation_payload_hash"),
                sequence == null ? null : sequence.longValue());
    }

    private static CalendarRegistrationView view(
            RegistrationRow row,
            CalendarRegistrationAccess.Target target) {
        return new CalendarRegistrationView(
                row.id(),
                row.planId(),
                target.scope(),
                row.state(),
                row.revision(),
                row.queueSequence());
    }

    private void insertHistory(
            UUID registrationId,
            CalendarRegistrationState state,
            long revision,
            String requestHash,
            String payloadHash,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        String event = switch (state) {
            case COMMITTED -> "JOINED";
            case WAITLISTED -> "WAITLISTED";
            case APPROVAL_REQUIRED -> "APPROVAL_REQUESTED";
            default -> throw new IllegalStateException(
                    "Unsupported join state " + state);
        };
        jdbc.update(
                """
                INSERT INTO calendar_registration_history (
                    id, registration_id, plan_id, activity_id,
                    event_type, previous_state, current_state,
                    status_origin, registration_revision,
                    operation_request_hash, operation_payload_hash,
                    reason_present, occurred_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (
                    ?, ?, ?, ?, ?, NULL, ?, 'USER', ?, ?, ?,
                    FALSE, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                registrationId,
                target.planId(),
                target.activityId(),
                event,
                state.name(),
                revision,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private void recordReceipt(
            String requestHash,
            String payloadHash,
            RegistrationRow row,
            CalendarRegistrationAccess.Target target,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO calendar_registration_request_receipt (
                    id, request_kind, plan_id, activity_id,
                    registration_id, operation_request_hash,
                    operation_payload_hash,
                    semantic_identity_hash, result_state,
                    result_revision, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (
                    ?, 'REGISTRATION_CHANGE', ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    workspace_id, created_by_user_id,
                    request_kind, operation_request_hash)
                DO NOTHING
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                row.id(),
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "REGISTRATION|"
                                + row.id()
                                + "|"
                                + row.revision()),
                row.state().name(),
                row.revision(),
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private static void requireCommand(
            CalendarRegistrationJoinCommand command) {
        if (command == null
                || command.planId() == null
                || command.scope() == null) {
            throw new IllegalArgumentException(
                    "A registration request and target are required");
        }
        CalendarParticipationAccess.hash(command.requestId());
    }

    private static void requirePayload(
            String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The registration request key was used for another change");
        }
    }

    private record Bucket(
            UUID id,
            UUID policyId,
            Instant opensAt,
            Instant closesAt,
            Integer capacity,
            CalendarLateJoinPolicy lateJoinPolicy,
            boolean waitlistEnabled,
            long policyRevision,
            int committed,
            int waitlisted,
            int overCapacity,
            long nextQueueSequence,
            long revision) {}

    private record RegistrationRow(
            UUID id,
            UUID planId,
            UUID activityId,
            String scopeType,
            CalendarRegistrationState state,
            long revision,
            String payloadHash,
            Long queueSequence) {}
}
