package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarRegistrationPolicyService {

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarRegistrationPolicyService(
            CalendarRegistrationAccess access,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarRegistrationPolicyView configure(
            CalendarRegistrationPolicyChange command) {
        requireCommand(command);
        CalendarRegistrationAccess.Target target =
                access.lockTarget(command.planId(), command.scope());
        access.requireOwner(target);
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.scope().type()
                        + "|"
                        + command.scope().targetId()
                        + "|"
                        + command.opensAt()
                        + "|"
                        + command.closesAt()
                        + "|"
                        + command.zoneId()
                        + "|"
                        + command.capacity()
                        + "|"
                        + command.lateJoinPolicy()
                        + "|"
                        + command.waitlistEnabled()
                        + "|"
                        + command.promotionMode()
                        + "|"
                        + command.limitMode()
                        + "|"
                        + command.offerTtl()
                        + "|"
                        + command.lateNotificationPolicy()
                        + "|"
                        + command.participantRosterVisible());
        access.lock("registration-policy-request|" + requestHash);
        access.lock("registration-policy-target|"
                + target.context().workspaceId()
                + "|"
                + target.planId()
                + "|"
                + target.activityId());
        PolicyRow replay = byRequest(requestHash, target);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            return view(replay);
        }
        PolicyRow current = currentForUpdate(target);
        if (current == null && command.expectedRevision() != 0) {
            throw revisionConflict();
        }
        if (current != null
                && current.revision() != command.expectedRevision()) {
            throw revisionConflict();
        }
        Instant now = Instant.now(clock);
        long revision = current == null ? 1 : current.revision() + 1;
        int committed = current == null
                ? 0
                : committedCount(target);
        CalendarCapacityState capacityState =
                command.capacity() != null
                                && committed > command.capacity()
                        ? CalendarCapacityState.OVER_CAPACITY
                        : CalendarCapacityState.WITHIN_CAPACITY;
        UUID policyId =
                current == null ? UUID.randomUUID() : current.id();
        if (current == null) {
            jdbc.update(
                    """
                    INSERT INTO calendar_registration_policy (
                        id, plan_id, activity_id, target_scope,
                        registration_scope, opens_at, closes_at,
                        zone_id, capacity, late_join_policy,
                        waitlist_enabled, promotion_mode, limit_mode,
                        offer_ttl_seconds, late_notification_policy,
                        participant_roster_visible, capacity_state,
                        policy_revision, operation_request_hash,
                        operation_payload_hash, created_at, updated_at,
                        workspace_id, source_created_by_user_id)
                    VALUES (
                        ?, ?, ?, ?, 'ONE_OFF', ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                    """,
                    policyId,
                    target.planId(),
                    target.activityId(),
                    target.scopeType().name(),
                    timestamp(command.opensAt()),
                    timestamp(command.closesAt()),
                    command.zoneId(),
                    command.capacity(),
                    command.lateJoinPolicy().name(),
                    command.waitlistEnabled(),
                    command.promotionMode().name(),
                    command.limitMode().name(),
                    command.offerTtl().toSeconds(),
                    command.lateNotificationPolicy().name(),
                    command.participantRosterVisible(),
                    capacityState.name(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.sourceOwnerId());
            jdbc.update(
                    """
                    INSERT INTO calendar_capacity_bucket (
                        id, policy_id, plan_id, activity_id,
                        committed_count, waitlisted_count,
                        over_capacity_count, next_queue_sequence,
                        bucket_revision, created_at, updated_at,
                        workspace_id, source_created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, 0, ?, 1, 1, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    policyId,
                    target.planId(),
                    target.activityId(),
                    committed,
                    overCapacity(committed, command.capacity()),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.sourceOwnerId());
        } else {
            int changed = jdbc.update(
                    """
                    UPDATE calendar_registration_policy
                    SET opens_at = ?, closes_at = ?, zone_id = ?,
                        capacity = ?, late_join_policy = ?,
                        waitlist_enabled = ?, promotion_mode = ?,
                        limit_mode = ?, offer_ttl_seconds = ?,
                        late_notification_policy = ?,
                        participant_roster_visible = ?,
                        capacity_state = ?,
                        policy_revision = policy_revision + 1,
                        operation_request_hash = ?,
                        operation_payload_hash = ?, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND source_created_by_user_id = ?
                      AND policy_revision = ?
                    """,
                    timestamp(command.opensAt()),
                    timestamp(command.closesAt()),
                    command.zoneId(),
                    command.capacity(),
                    command.lateJoinPolicy().name(),
                    command.waitlistEnabled(),
                    command.promotionMode().name(),
                    command.limitMode().name(),
                    command.offerTtl().toSeconds(),
                    command.lateNotificationPolicy().name(),
                    command.participantRosterVisible(),
                    capacityState.name(),
                    requestHash,
                    payloadHash,
                    Timestamp.from(now),
                    current.id(),
                    target.context().workspaceId(),
                    target.sourceOwnerId(),
                    command.expectedRevision());
            if (changed != 1) {
                throw revisionConflict();
            }
            jdbc.update(
                    """
                    UPDATE calendar_capacity_bucket
                    SET committed_count = ?, over_capacity_count = ?,
                        bucket_revision = bucket_revision + 1,
                        updated_at = ?
                    WHERE policy_id = ? AND workspace_id = ?
                      AND source_created_by_user_id = ?
                    """,
                    committed,
                    overCapacity(committed, command.capacity()),
                    Timestamp.from(now),
                    current.id(),
                    target.context().workspaceId(),
                    target.sourceOwnerId());
        }
        recordReceipt(
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "POLICY|" + policyId + "|" + revision),
                policyId,
                capacityState,
                revision,
                now,
                target);
        return view(currentForUpdate(target));
    }

    private void recordReceipt(
            String requestHash,
            String payloadHash,
            String semanticHash,
            UUID policyId,
            CalendarCapacityState state,
            long revision,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        jdbc.update(
                """
                INSERT INTO calendar_registration_request_receipt (
                    id, request_kind, plan_id, activity_id,
                    operation_request_hash, operation_payload_hash,
                    semantic_identity_hash, result_state,
                    result_revision, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (
                    ?, 'POLICY_CHANGE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                    workspace_id, created_by_user_id,
                    request_kind, operation_request_hash)
                DO NOTHING
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                requestHash,
                payloadHash,
                semanticHash,
                state.name(),
                revision,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private PolicyRow byRequest(
            String requestHash,
            CalendarRegistrationAccess.Target target) {
        List<PolicyRow> rows = jdbc.query(
                selectPolicy()
                        + """
                         AND policy.operation_request_hash = ?
                        """,
                CalendarRegistrationPolicyService::row,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                requestHash);
        return rows.stream().findFirst().orElse(null);
    }

    private PolicyRow currentForUpdate(
            CalendarRegistrationAccess.Target target) {
        List<PolicyRow> rows = jdbc.query(
                selectPolicy() + " FOR UPDATE",
                CalendarRegistrationPolicyService::row,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        return rows.stream().findFirst().orElse(null);
    }

    private static String selectPolicy() {
        return """
                SELECT policy.id, policy.plan_id, policy.activity_id,
                       policy.target_scope, policy.opens_at,
                       policy.closes_at, policy.zone_id,
                       policy.capacity, policy.late_join_policy,
                       policy.waitlist_enabled, policy.promotion_mode,
                       policy.limit_mode, policy.offer_ttl_seconds,
                       policy.late_notification_policy,
                       policy.participant_roster_visible,
                       policy.capacity_state, policy.policy_revision,
                       policy.operation_payload_hash
                FROM calendar_registration_policy policy
                WHERE policy.plan_id = ?
                  AND policy.activity_id IS NOT DISTINCT FROM ?
                  AND policy.workspace_id = ?
                  AND policy.source_created_by_user_id = ?
                """;
    }

    private int committedCount(
            CalendarRegistrationAccess.Target target) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_registration
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND registration_state = 'COMMITTED'
                """,
                Integer.class,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        return count == null ? 0 : count;
    }

    private static CalendarRegistrationPolicyView view(
            PolicyRow row) {
        UUID targetId =
                row.activityId() == null ? row.planId() : row.activityId();
        return new CalendarRegistrationPolicyView(
                row.planId(),
                new CalendarParticipationScope(
                        CalendarParticipationScopeType.valueOf(
                                row.scopeType()),
                        targetId),
                row.opensAt(),
                row.closesAt(),
                row.zoneId(),
                row.capacity(),
                CalendarLateJoinPolicy.valueOf(row.lateJoinPolicy()),
                row.waitlistEnabled(),
                CalendarWaitlistPromotionMode.valueOf(
                        row.promotionMode()),
                CalendarRegistrationLimitMode.valueOf(row.limitMode()),
                Duration.ofSeconds(row.offerTtlSeconds()),
                CalendarLateNotificationPolicy.valueOf(
                        row.lateNotificationPolicy()),
                row.participantRosterVisible(),
                CalendarCapacityState.valueOf(row.capacityState()),
                row.revision());
    }

    private static PolicyRow row(
            java.sql.ResultSet result, int ignored)
            throws java.sql.SQLException {
        Timestamp opens = result.getTimestamp("opens_at");
        Timestamp closes = result.getTimestamp("closes_at");
        Number capacity = (Number) result.getObject("capacity");
        return new PolicyRow(
                result.getObject("id", UUID.class),
                result.getObject("plan_id", UUID.class),
                result.getObject("activity_id", UUID.class),
                result.getString("target_scope"),
                opens == null ? null : opens.toInstant(),
                closes == null ? null : closes.toInstant(),
                result.getString("zone_id"),
                capacity == null ? null : capacity.intValue(),
                result.getString("late_join_policy"),
                result.getBoolean("waitlist_enabled"),
                result.getString("promotion_mode"),
                result.getString("limit_mode"),
                result.getLong("offer_ttl_seconds"),
                result.getString("late_notification_policy"),
                result.getBoolean("participant_roster_visible"),
                result.getString("capacity_state"),
                result.getLong("policy_revision"),
                result.getString("operation_payload_hash"));
    }

    private static void requireCommand(
            CalendarRegistrationPolicyChange command) {
        if (command == null
                || command.planId() == null
                || command.scope() == null
                || command.lateJoinPolicy() == null
                || command.promotionMode() == null
                || command.limitMode() == null
                || command.offerTtl() == null
                || command.lateNotificationPolicy() == null
                || command.expectedRevision() < 0
                || command.capacity() != null
                        && command.capacity() < 1
                || command.offerTtl().compareTo(Duration.ofMinutes(1)) < 0
                || command.offerTtl().compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException(
                    "A valid registration policy and revision are required");
        }
        ZoneId.of(command.zoneId());
        if (command.opensAt() != null
                && command.closesAt() != null
                && !command.closesAt().isAfter(command.opensAt())) {
            throw new IllegalArgumentException(
                    "Registration close time must follow its open time");
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static int overCapacity(int committed, Integer capacity) {
        return capacity == null ? 0 : Math.max(0, committed - capacity);
    }

    private static void requirePayload(
            String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The policy request key was used for another change");
        }
    }

    private static BusinessException revisionConflict() {
        return new BusinessException(
                "CALENDAR_REGISTRATION_POLICY_REVISION_CONFLICT",
                "Registration policy changed; reload before updating it");
    }

    private record PolicyRow(
            UUID id,
            UUID planId,
            UUID activityId,
            String scopeType,
            Instant opensAt,
            Instant closesAt,
            String zoneId,
            Integer capacity,
            String lateJoinPolicy,
            boolean waitlistEnabled,
            String promotionMode,
            String limitMode,
            long offerTtlSeconds,
            String lateNotificationPolicy,
            boolean participantRosterVisible,
            String capacityState,
            long revision,
            String payloadHash) {}
}
