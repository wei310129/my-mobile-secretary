package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarOccurrenceKey;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.Date;
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
public class CalendarRecurringRegistrationService {

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarRecurringRegistrationService(
            CalendarRegistrationAccess access, JdbcTemplate jdbc, Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void configure(CalendarRecurringRegistrationPolicyCommand command) {
        requirePolicy(command);
        var target = access.lockTarget(command.planId(), command.target());
        access.requireOwner(target);
        UUID policyId = basePolicyId(target);
        int inserted = jdbc.update(
                """
                INSERT INTO calendar_recurring_registration_policy (
                    policy_id, plan_id, series_id,
                    recurrence_rule_revision, registration_scope,
                    operation_request_hash, created_at, workspace_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (policy_id) DO NOTHING
                """,
                policyId,
                target.planId(),
                command.seriesId(),
                command.recurrenceRuleRevision(),
                command.registrationScope().name(),
                CalendarParticipationAccess.hash(command.requestId()),
                Timestamp.from(Instant.now(clock)),
                target.context().workspaceId(),
                target.sourceOwnerId());
        if (inserted == 0) {
            Policy policy = policy(policyId);
            if (!policy.seriesId().equals(command.seriesId())
                    || policy.ruleRevision()
                            != command.recurrenceRuleRevision()
                    || policy.scope() != command.registrationScope()) {
                throw new BusinessException(
                        "CALENDAR_RECURRING_REGISTRATION_SCOPE_CHANGE_REQUIRES_REVIEW",
                        "Recurring registration scope cannot change after configuration");
            }
        }
    }

    public CalendarRecurringRegistrationView join(
            CalendarRecurringRegistrationJoinCommand command) {
        requireJoin(command);
        var target = access.lockTarget(command.planId(), command.target());
        Policy policy = policy(basePolicyId(target));
        requirePolicyMatch(command, policy);
        CalendarOccurrenceKey bucketKey = bucketKey(policy.scope(), command.occurrenceKey());
        access.lock("recurring-registration|"
                + policy.policyId()
                + "|"
                + keyText(bucketKey));
        Bucket bucket = bucket(policy, bucketKey, target);
        Registration current = current(bucket.id(), target.context().actorId());
        if (current != null
                && current.state() != CalendarRegistrationState.WITHDRAWN_BY_USER) {
            return view(current, policy.scope(), bucketKey);
        }
        BasePolicy base = basePolicy(policy.policyId());
        Instant now = Instant.now(clock);
        if ((base.opensAt() != null && now.isBefore(base.opensAt()))
                || (base.closesAt() != null && !now.isBefore(base.closesAt()))) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_CLOSED",
                    "Recurring registration is outside its configured window");
        }
        CalendarRegistrationState state =
                base.capacity() == null || bucket.committed() < base.capacity()
                        ? CalendarRegistrationState.COMMITTED
                        : requireWaitlist(base.waitlistEnabled());
        UUID id = current == null ? UUID.randomUUID() : current.id();
        String requestHash = CalendarParticipationAccess.hash(command.requestId());
        if (current == null) {
            jdbc.update(
                    """
                    INSERT INTO calendar_recurring_registration (
                        id, bucket_id, policy_id, plan_id, series_id,
                        recurrence_rule_revision, registration_scope,
                        logical_timed_start, logical_all_day_start,
                        registration_state, registration_revision,
                        operation_request_hash, joined_at, updated_at,
                        workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    bucket.id(),
                    policy.policyId(),
                    target.planId(),
                    policy.seriesId(),
                    policy.ruleRevision(),
                    policy.scope().name(),
                    timed(bucketKey),
                    allDay(bucketKey),
                    state.name(),
                    requestHash,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    target.context().workspaceId(),
                    target.context().actorId(),
                    target.sourceOwnerId());
        } else {
            jdbc.update(
                    """
                    UPDATE calendar_recurring_registration
                    SET registration_state = ?,
                        registration_revision = registration_revision + 1,
                        operation_request_hash = ?, updated_at = ?
                    WHERE id = ? AND registration_revision = ?
                    """,
                    state.name(),
                    requestHash,
                    Timestamp.from(now),
                    current.id(),
                    current.revision());
        }
        updateBucket(bucket, state, 1, now);
        return new CalendarRecurringRegistrationView(
                id,
                policy.scope(),
                state,
                bucketKey,
                current == null ? 1 : current.revision() + 1);
    }

    public CalendarRecurringRegistrationView skipOccurrence(
            String requestId,
            UUID planId,
            CalendarParticipationScope targetScope,
            UUID seriesId,
            int ruleRevision,
            CalendarOccurrenceKey occurrenceKey) {
        CalendarParticipationAccess.hash(requestId);
        var target = access.lockTarget(planId, targetScope);
        Policy policy = policy(basePolicyId(target));
        if (!policy.seriesId().equals(seriesId)
                || policy.ruleRevision() != ruleRevision) {
            throw new BusinessException(
                    "CALENDAR_RECURRENCE_REVISION_CHANGED",
                    "Recurring registration revision changed");
        }
        CalendarOccurrenceKey key = bucketKey(policy.scope(), occurrenceKey);
        Bucket bucket = bucket(policy, key, target);
        Registration current = current(bucket.id(), target.context().actorId());
        if (policy.scope() == CalendarRecurringRegistrationScope.SERIES) {
            if (current == null) {
                throw new BusinessException(
                        "CALENDAR_REGISTRATION_REQUIRED",
                        "A series registration is required");
            }
            return view(current, policy.scope(), occurrenceKey);
        }
        if (current == null
                || current.state() == CalendarRegistrationState.WITHDRAWN_BY_USER) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_REQUIRED",
                    "An occurrence registration is required");
        }
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                UPDATE calendar_recurring_registration
                SET registration_state = 'WITHDRAWN_BY_USER',
                    registration_revision = registration_revision + 1,
                    operation_request_hash = ?, updated_at = ?
                WHERE id = ? AND registration_revision = ?
                """,
                CalendarParticipationAccess.hash(requestId),
                Timestamp.from(now),
                current.id(),
                current.revision());
        updateBucket(bucket, current.state(), -1, now);
        return new CalendarRecurringRegistrationView(
                current.id(),
                policy.scope(),
                CalendarRegistrationState.WITHDRAWN_BY_USER,
                key,
                current.revision() + 1);
    }

    private UUID basePolicyId(CalendarRegistrationAccess.Target target) {
        List<UUID> ids = jdbc.queryForList(
                """
                SELECT id FROM calendar_registration_policy
                WHERE plan_id = ? AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ? AND source_created_by_user_id = ?
                """,
                UUID.class,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        if (ids.size() != 1) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_POLICY_REQUIRED",
                    "A base registration policy is required");
        }
        return ids.getFirst();
    }

    private Policy policy(UUID policyId) {
        return jdbc.query(
                        """
                        SELECT policy_id, series_id,
                               recurrence_rule_revision, registration_scope
                        FROM calendar_recurring_registration_policy
                        WHERE policy_id = ?
                        """,
                        (row, ignored) -> new Policy(
                                row.getObject("policy_id", UUID.class),
                                row.getObject("series_id", UUID.class),
                                row.getInt("recurrence_rule_revision"),
                                CalendarRecurringRegistrationScope.valueOf(
                                        row.getString("registration_scope"))),
                        policyId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "CALENDAR_RECURRING_REGISTRATION_POLICY_REQUIRED",
                        "Recurring registration is not configured"));
    }

    private BasePolicy basePolicy(UUID policyId) {
        return jdbc.queryForObject(
                """
                SELECT opens_at, closes_at, capacity, waitlist_enabled
                FROM calendar_registration_policy WHERE id = ?
                """,
                (row, ignored) -> new BasePolicy(
                        instant(row.getTimestamp("opens_at")),
                        instant(row.getTimestamp("closes_at")),
                        (Integer) row.getObject("capacity"),
                        row.getBoolean("waitlist_enabled")),
                policyId);
    }

    private Bucket bucket(
            Policy policy,
            CalendarOccurrenceKey key,
            CalendarRegistrationAccess.Target target) {
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                INSERT INTO calendar_recurring_capacity_bucket (
                    id, policy_id, plan_id, series_id,
                    recurrence_rule_revision, registration_scope,
                    logical_timed_start, logical_all_day_start,
                    committed_count, waitlisted_count, bucket_revision,
                    created_at, updated_at, workspace_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 1, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                policy.policyId(),
                target.planId(),
                policy.seriesId(),
                policy.ruleRevision(),
                policy.scope().name(),
                timed(key),
                allDay(key),
                Timestamp.from(now),
                Timestamp.from(now),
                target.context().workspaceId(),
                target.sourceOwnerId());
        return jdbc.queryForObject(
                """
                SELECT id, committed_count, waitlisted_count, bucket_revision
                FROM calendar_recurring_capacity_bucket
                WHERE policy_id = ?
                  AND logical_timed_start IS NOT DISTINCT FROM ?
                  AND logical_all_day_start IS NOT DISTINCT FROM ?
                FOR UPDATE
                """,
                (row, ignored) -> new Bucket(
                        row.getObject("id", UUID.class),
                        row.getInt("committed_count"),
                        row.getInt("waitlisted_count"),
                        row.getLong("bucket_revision")),
                policy.policyId(),
                timed(key),
                allDay(key));
    }

    private Registration current(UUID bucketId, UUID actorId) {
        return jdbc.query(
                        """
                        SELECT id, registration_state, registration_revision
                        FROM calendar_recurring_registration
                        WHERE bucket_id = ? AND created_by_user_id = ?
                        FOR UPDATE
                        """,
                        (row, ignored) -> new Registration(
                                row.getObject("id", UUID.class),
                                CalendarRegistrationState.valueOf(
                                        row.getString("registration_state")),
                                row.getLong("registration_revision")),
                        bucketId,
                        actorId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void updateBucket(
            Bucket bucket,
            CalendarRegistrationState state,
            int direction,
            Instant now) {
        int committed = state == CalendarRegistrationState.COMMITTED ? direction : 0;
        int waitlisted = state == CalendarRegistrationState.WAITLISTED ? direction : 0;
        jdbc.update(
                """
                UPDATE calendar_recurring_capacity_bucket
                SET committed_count = committed_count + ?,
                    waitlisted_count = waitlisted_count + ?,
                    bucket_revision = bucket_revision + 1, updated_at = ?
                WHERE id = ? AND bucket_revision = ?
                """,
                committed,
                waitlisted,
                Timestamp.from(now),
                bucket.id(),
                bucket.revision());
    }

    private static CalendarOccurrenceKey bucketKey(
            CalendarRecurringRegistrationScope scope, CalendarOccurrenceKey key) {
        if (scope == CalendarRecurringRegistrationScope.EACH_OCCURRENCE
                && key == null) {
            throw new IllegalArgumentException(
                    "Each-occurrence registration requires an occurrence key");
        }
        return scope == CalendarRecurringRegistrationScope.SERIES ? null : key;
    }

    private static void requirePolicy(
            CalendarRecurringRegistrationPolicyCommand command) {
        if (command == null
                || command.planId() == null
                || command.target() == null
                || command.seriesId() == null
                || command.recurrenceRuleRevision() <= 0
                || command.registrationScope() == null) {
            throw new IllegalArgumentException(
                    "A complete recurring registration policy is required");
        }
        CalendarParticipationAccess.hash(command.requestId());
    }

    private static void requireJoin(CalendarRecurringRegistrationJoinCommand command) {
        if (command == null
                || command.planId() == null
                || command.target() == null
                || command.seriesId() == null
                || command.recurrenceRuleRevision() <= 0) {
            throw new IllegalArgumentException(
                    "A complete recurring registration request is required");
        }
        CalendarParticipationAccess.hash(command.requestId());
    }

    private static void requirePolicyMatch(
            CalendarRecurringRegistrationJoinCommand command, Policy policy) {
        if (!policy.seriesId().equals(command.seriesId())
                || policy.ruleRevision() != command.recurrenceRuleRevision()) {
            throw new BusinessException(
                    "CALENDAR_RECURRENCE_REVISION_CHANGED",
                    "Recurring registration revision changed");
        }
    }

    private static CalendarRegistrationState requireWaitlist(boolean enabled) {
        if (!enabled) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_FULL",
                    "Recurring registration is full");
        }
        return CalendarRegistrationState.WAITLISTED;
    }

    private static CalendarRecurringRegistrationView view(
            Registration registration,
            CalendarRecurringRegistrationScope scope,
            CalendarOccurrenceKey key) {
        return new CalendarRecurringRegistrationView(
                registration.id(),
                scope,
                registration.state(),
                key,
                registration.revision());
    }

    private static Timestamp timed(CalendarOccurrenceKey key) {
        return key == null || !key.timed()
                ? null
                : Timestamp.valueOf(key.timedStart());
    }

    private static Date allDay(CalendarOccurrenceKey key) {
        return key == null || key.timed() ? null : Date.valueOf(key.allDayStart());
    }

    private static String keyText(CalendarOccurrenceKey key) {
        return key == null ? "SERIES" : key.toString();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record Policy(
            UUID policyId,
            UUID seriesId,
            int ruleRevision,
            CalendarRecurringRegistrationScope scope) {}

    private record BasePolicy(
            Instant opensAt,
            Instant closesAt,
            Integer capacity,
            boolean waitlistEnabled) {}

    private record Bucket(UUID id, int committed, int waitlisted, long revision) {}

    private record Registration(
            UUID id, CalendarRegistrationState state, long revision) {}
}
