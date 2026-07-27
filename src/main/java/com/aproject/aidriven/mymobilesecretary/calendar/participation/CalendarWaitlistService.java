package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
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
public class CalendarWaitlistService {

    private final CalendarRegistrationAccess access;
    private final CalendarParticipationService participations;
    private final CalendarRegistrationService registrations;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarWaitlistService(
            CalendarRegistrationAccess access,
            CalendarParticipationService participations,
            CalendarRegistrationService registrations,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.participations = participations;
        this.registrations = registrations;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarWaitlistOfferView offerNext(
            String requestId,
            UUID planId,
            CalendarParticipationScope scope) {
        String requestHash =
                CalendarParticipationAccess.hash(requestId);
        CalendarRegistrationAccess.Target target =
                access.lockTarget(planId, scope);
        access.requireManager(target, false);
        access.lock("waitlist-offer-request|" + requestHash);
        List<CalendarWaitlistOfferView> replay =
                offerByRequest(requestHash, target);
        if (!replay.isEmpty()) {
            return replay.getFirst();
        }
        Bucket bucket = lockBucket(target);
        if (bucket.capacity() != null
                && bucket.committed() >= bucket.capacity()) {
            throw new BusinessException(
                    "CALENDAR_WAITLIST_NO_AVAILABLE_SEAT",
                    "A waitlist offer requires available capacity");
        }
        List<Entry> entries = jdbc.query(
                """
                SELECT entry.id, entry.registration_id,
                       entry.created_by_user_id,
                       entry.queue_sequence,
                       entry.entry_revision
                FROM calendar_waitlist_entry entry
                WHERE entry.plan_id = ?
                  AND entry.activity_id IS NOT DISTINCT FROM ?
                  AND entry.workspace_id = ?
                  AND entry.source_created_by_user_id = ?
                  AND entry.entry_state = 'WAITING'
                ORDER BY entry.queue_sequence, entry.id
                LIMIT 1
                FOR UPDATE
                """,
                (row, ignored) -> new Entry(
                        row.getObject("id", UUID.class),
                        row.getObject("registration_id", UUID.class),
                        row.getObject("created_by_user_id", UUID.class),
                        row.getLong("queue_sequence"),
                        row.getLong("entry_revision")),
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
        if (entries.isEmpty()) {
            throw new NotFoundException(
                    "Calendar waitlist", "next waiting participant");
        }
        Entry entry = entries.getFirst();
        String payloadHash = CalendarParticipationAccess.hash(
                target.planId()
                        + "|"
                        + target.activityId()
                        + "|"
                        + entry.id()
                        + "|OFFER");
        Instant now = Instant.now(clock);
        Instant expiresAt =
                now.plusSeconds(bucket.offerTtlSeconds());
        UUID offerId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_waitlist_offer (
                    id, entry_id, registration_id, policy_id,
                    plan_id, activity_id, offer_status,
                    applied_policy_revision, offer_revision,
                    operation_request_hash, operation_payload_hash,
                    offered_at, expires_at, responded_at,
                    updated_at, workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (
                    ?, ?, ?, ?, ?, ?, 'OFFERED', ?, 1, ?, ?,
                    ?, ?, NULL, ?, ?, ?, ?)
                """,
                offerId,
                entry.id(),
                entry.registrationId(),
                bucket.policyId(),
                target.planId(),
                target.activityId(),
                bucket.policyRevision(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                target.context().workspaceId(),
                entry.actorId(),
                target.sourceOwnerId());
        jdbc.update(
                """
                UPDATE calendar_waitlist_entry
                SET entry_state = 'OFFERED',
                    entry_revision = entry_revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND entry_revision = ?
                """,
                Timestamp.from(now),
                entry.id(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                entry.revision());
        return new CalendarWaitlistOfferView(
                offerId,
                target.planId(),
                target.scope(),
                expiresAt,
                "OFFERED",
                1);
    }

    public CalendarRegistrationView accept(
            CalendarWaitlistOfferResponse response) {
        if (response == null
                || response.offerId() == null
                || response.expectedRevision() < 1) {
            throw new IllegalArgumentException(
                    "A waitlist offer and revision are required");
        }
        String requestHash =
                CalendarParticipationAccess.hash(response.requestId());
        OfferIdentity identity = offerIdentity(response.offerId());
        CalendarParticipationScope scope = identity.activityId() == null
                ? CalendarParticipationScope.plan(identity.planId())
                : CalendarParticipationScope.activity(
                        identity.activityId());
        CalendarRegistrationAccess.Target target =
                access.lockTarget(identity.planId(), scope);
        access.lock("waitlist-response-request|" + requestHash);
        List<UUID> receiptRegistrations = jdbc.queryForList(
                """
                SELECT registration_id
                FROM calendar_registration_request_receipt
                WHERE request_kind = 'WAITLIST_RESPONSE'
                  AND operation_request_hash = ?
                  AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                UUID.class,
                requestHash,
                target.context().workspaceId(),
                target.context().actorId());
        if (!receiptRegistrations.isEmpty()) {
            return registrations
                    .current(identity.planId(), scope)
                    .orElseThrow();
        }
        Bucket bucket = lockBucket(target);
        Offer offer = lockOffer(response.offerId(), target);
        if (offer.revision() != response.expectedRevision()) {
            throw new BusinessException(
                    "CALENDAR_WAITLIST_OFFER_REVISION_CONFLICT",
                    "Waitlist offer changed; reload before responding");
        }
        Instant now = Instant.now(clock);
        if (!"OFFERED".equals(offer.status())
                || !now.isBefore(offer.expiresAt())
                || offer.policyRevision() != bucket.policyRevision()) {
            throw new BusinessException(
                    "CALENDAR_WAITLIST_OFFER_STALE",
                    "Waitlist offer expired or its policy changed");
        }
        if (bucket.capacity() != null
                && bucket.committed() >= bucket.capacity()) {
            throw new BusinessException(
                    "CALENDAR_WAITLIST_NO_AVAILABLE_SEAT",
                    "The offered seat is no longer available");
        }
        CalendarParticipationView currentParticipation =
                participations.current(identity.planId(), scope)
                        .orElseThrow();
        participations.change(new CalendarParticipationChange(
                response.requestId() + "|participation",
                identity.planId(),
                scope,
                CalendarParticipationStatus.COMMITTED,
                currentParticipation.revision()));
        String payloadHash = CalendarParticipationAccess.hash(
                response.offerId()
                        + "|"
                        + response.expectedRevision()
                        + "|ACCEPT");
        int registrationChanged = jdbc.update(
                """
                UPDATE calendar_registration
                SET registration_state = 'COMMITTED',
                    status_origin = 'USER',
                    applied_policy_revision = ?,
                    registration_revision =
                        registration_revision + 1,
                    operation_request_hash = ?,
                    operation_payload_hash = ?,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND registration_state = 'WAITLISTED'
                """,
                bucket.policyRevision(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                offer.registrationId(),
                target.context().workspaceId(),
                target.context().actorId());
        if (registrationChanged != 1) {
            throw new BusinessException(
                    "CALENDAR_REGISTRATION_CHANGED",
                    "Registration changed before the offer was accepted");
        }
        long registrationRevision = jdbc.queryForObject(
                """
                SELECT registration_revision
                FROM calendar_registration
                WHERE id = ?
                """,
                Long.class,
                offer.registrationId());
        jdbc.update(
                """
                UPDATE calendar_waitlist_offer
                SET offer_status = 'ACCEPTED',
                    offer_revision = offer_revision + 1,
                    responded_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND offer_status = 'OFFERED'
                  AND offer_revision = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                offer.id(),
                target.context().workspaceId(),
                target.context().actorId(),
                response.expectedRevision());
        jdbc.update(
                """
                UPDATE calendar_waitlist_entry
                SET entry_state = 'PROMOTED',
                    entry_revision = entry_revision + 1,
                    exited_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND entry_state = 'OFFERED'
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                offer.entryId(),
                target.context().workspaceId(),
                target.context().actorId());
        jdbc.update(
                """
                UPDATE calendar_capacity_bucket
                SET committed_count = committed_count + 1,
                    waitlisted_count = waitlisted_count - 1,
                    bucket_revision = bucket_revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND bucket_revision = ?
                """,
                Timestamp.from(now),
                bucket.id(),
                target.context().workspaceId(),
                target.sourceOwnerId(),
                bucket.revision());
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
                    ?, ?, ?, ?, 'OFFER_ACCEPTED', 'WAITLISTED',
                    'COMMITTED', 'USER', ?, ?, ?, FALSE, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                offer.registrationId(),
                target.planId(),
                target.activityId(),
                registrationRevision,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        recordResponseReceipt(
                requestHash,
                payloadHash,
                offer,
                registrationRevision,
                now,
                target);
        return registrations.current(identity.planId(), scope)
                .orElseThrow();
    }

    private void recordResponseReceipt(
            String requestHash,
            String payloadHash,
            Offer offer,
            long registrationRevision,
            Instant now,
            CalendarRegistrationAccess.Target target) {
        jdbc.update(
                """
                INSERT INTO calendar_registration_request_receipt (
                    id, request_kind, plan_id, activity_id,
                    registration_id, waitlist_entry_id,
                    waitlist_offer_id, operation_request_hash,
                    operation_payload_hash,
                    semantic_identity_hash, result_state,
                    result_revision, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (
                    ?, 'WAITLIST_RESPONSE', ?, ?, ?, ?, ?, ?, ?,
                    ?, 'COMMITTED', ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                target.planId(),
                target.activityId(),
                offer.registrationId(),
                offer.entryId(),
                offer.id(),
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "WAITLIST_ACCEPT|"
                                + offer.id()
                                + "|"
                                + registrationRevision),
                registrationRevision,
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private OfferIdentity offerIdentity(UUID offerId) {
        List<OfferIdentity> rows = jdbc.query(
                """
                SELECT plan_id, activity_id
                FROM calendar_waitlist_offer
                WHERE id = ?
                """,
                (row, ignored) -> new OfferIdentity(
                        row.getObject("plan_id", UUID.class),
                        row.getObject("activity_id", UUID.class)),
                offerId);
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar waitlist offer", "requested offer");
        }
        return rows.getFirst();
    }

    private Offer lockOffer(
            UUID offerId,
            CalendarRegistrationAccess.Target target) {
        List<Offer> rows = jdbc.query(
                """
                SELECT id, entry_id, registration_id,
                       offer_status, applied_policy_revision,
                       offer_revision, expires_at
                FROM calendar_waitlist_offer
                WHERE id = ? AND plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                FOR UPDATE
                """,
                (row, ignored) -> new Offer(
                        row.getObject("id", UUID.class),
                        row.getObject("entry_id", UUID.class),
                        row.getObject("registration_id", UUID.class),
                        row.getString("offer_status"),
                        row.getLong("applied_policy_revision"),
                        row.getLong("offer_revision"),
                        row.getTimestamp("expires_at").toInstant()),
                offerId,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar waitlist offer", "requested offer");
        }
        return rows.getFirst();
    }

    private Bucket lockBucket(
            CalendarRegistrationAccess.Target target) {
        List<Bucket> rows = jdbc.query(
                """
                SELECT bucket.id, bucket.policy_id,
                       policy.capacity, policy.offer_ttl_seconds,
                       policy.policy_revision,
                       bucket.committed_count,
                       bucket.waitlisted_count,
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
                    Number capacity =
                            (Number) row.getObject("capacity");
                    return new Bucket(
                            row.getObject("id", UUID.class),
                            row.getObject("policy_id", UUID.class),
                            capacity == null
                                    ? null
                                    : capacity.intValue(),
                            row.getLong("offer_ttl_seconds"),
                            row.getLong("policy_revision"),
                            row.getInt("committed_count"),
                            row.getInt("waitlisted_count"),
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

    private List<CalendarWaitlistOfferView> offerByRequest(
            String requestHash,
            CalendarRegistrationAccess.Target target) {
        return jdbc.query(
                """
                SELECT id, plan_id, activity_id, expires_at,
                       offer_status, offer_revision
                FROM calendar_waitlist_offer
                WHERE operation_request_hash = ?
                  AND plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND workspace_id = ?
                  AND source_created_by_user_id = ?
                """,
                (row, ignored) -> new CalendarWaitlistOfferView(
                        row.getObject("id", UUID.class),
                        row.getObject("plan_id", UUID.class),
                        target.scope(),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getString("offer_status"),
                        row.getLong("offer_revision")),
                requestHash,
                target.planId(),
                target.activityId(),
                target.context().workspaceId(),
                target.sourceOwnerId());
    }

    private record Entry(
            UUID id,
            UUID registrationId,
            UUID actorId,
            long queueSequence,
            long revision) {}

    private record OfferIdentity(UUID planId, UUID activityId) {}

    private record Offer(
            UUID id,
            UUID entryId,
            UUID registrationId,
            String status,
            long policyRevision,
            long revision,
            Instant expiresAt) {}

    private record Bucket(
            UUID id,
            UUID policyId,
            Integer capacity,
            long offerTtlSeconds,
            long policyRevision,
            int committed,
            int waitlisted,
            long revision) {}
}
