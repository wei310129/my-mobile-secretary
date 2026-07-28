package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarOwnershipTransferService {

    private static final Duration MIN_TTL = Duration.ofMinutes(1);
    private static final Duration MAX_TTL = Duration.ofDays(30);

    private final CalendarRegistrationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public CalendarOwnershipTransferService(
            CalendarRegistrationAccess access,
            JdbcTemplate jdbc,
            Clock clock,
            ApplicationEventPublisher events) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
        this.events = events;
    }

    public CalendarOwnershipTransferView offer(
            CalendarOwnershipTransferOffer command) {
        requireOffer(command);
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.planId()
                        + "|"
                        + command.targetOwnerUserId()
                        + "|"
                        + command.expectedOwnershipRevision()
                        + "|"
                        + command.timeToLive());
        access.lock("ownership-transfer-request|" + requestHash);
        Receipt replay = receipt(requestHash, context);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            return view(transfer(replay.transferId(), context, false));
        }
        access.lock("ownership-transfer-plan|" + command.planId());
        Ownership ownership =
                lockOwnership(command.planId(), context);
        if (!ownership.ownerUserId().equals(context.actorId())) {
            throw new SecurityException(
                    "Only the effective owner may offer ownership");
        }
        if (ownership.revision()
                != command.expectedOwnershipRevision()) {
            throw ownershipConflict();
        }
        requireActiveTarget(
                command.targetOwnerUserId(), context);
        if (context.actorId().equals(command.targetOwnerUserId())) {
            throw new BusinessException(
                    "CALENDAR_OWNERSHIP_TRANSFER_SELF",
                    "Ownership cannot be transferred to the current owner");
        }
        expireOfferedIfNeeded(ownership, context);
        if (activeOffer(ownership, context) != null) {
            throw new BusinessException(
                    "CALENDAR_OWNERSHIP_TRANSFER_ALREADY_OFFERED",
                    "This calendar already has an active ownership offer");
        }
        Instant now = monotonic(
                Instant.now(clock), ownership.updatedAt());
        Instant expiresAt = now.plus(command.timeToLive());
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_ownership_transfer (
                    id, plan_id, from_owner_user_id,
                    to_owner_user_id, transfer_status,
                    expected_ownership_revision, transfer_revision,
                    operation_request_hash, operation_payload_hash,
                    offered_at, expires_at, accepted_at,
                    canceled_at, updated_at, workspace_id,
                    source_created_by_user_id)
                VALUES (
                    ?, ?, ?, ?, 'OFFERED', ?, 1, ?, ?, ?, ?,
                    NULL, NULL, ?, ?, ?)
                """,
                id,
                command.planId(),
                context.actorId(),
                command.targetOwnerUserId(),
                ownership.revision(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                context.workspaceId(),
                ownership.sourceOwnerId());
        recordReceipt(
                id,
                command.planId(),
                CalendarOwnershipTransferStatus.OFFERED,
                1,
                requestHash,
                payloadHash,
                now,
                context,
                ownership.sourceOwnerId());
        insertOutbox(
                id,
                command.planId(),
                command.targetOwnerUserId(),
                CalendarOwnershipTransferStatus.OFFERED,
                requestHash,
                payloadHash,
                now,
                context,
                ownership.sourceOwnerId());
        events.publishEvent(new CalendarOwnershipTransferLifecycleEvent(
                id,
                command.planId(),
                CalendarOwnershipTransferLifecycleEvent.Action.OFFERED,
                now));
        return view(transfer(id, context, false));
    }

    public CalendarOwnershipTransferView accept(
            CalendarOwnershipTransferAcceptance command) {
        requireAcceptance(command);
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.transferId()
                        + "|"
                        + command.expectedTransferRevision()
                        + "|"
                        + command.expectedOwnershipRevision()
                        + "|ACCEPT");
        access.lock("ownership-transfer-request|" + requestHash);
        Receipt replay = receipt(requestHash, context);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            return view(transfer(replay.transferId(), context, false));
        }
        Transfer identity =
                transfer(command.transferId(), context, false);
        if (!identity.toOwnerUserId().equals(context.actorId())) {
            throw new SecurityException(
                    "Only the offered target may accept ownership");
        }
        access.lock("ownership-transfer-plan|" + identity.planId());
        Ownership ownership =
                lockOwnership(identity.planId(), context);
        Transfer transfer =
                transfer(command.transferId(), context, true);
        if (transfer.status()
                        != CalendarOwnershipTransferStatus.OFFERED
                || transfer.revision()
                        != command.expectedTransferRevision()
                || ownership.revision()
                        != command.expectedOwnershipRevision()
                || transfer.expectedOwnershipRevision()
                        != ownership.revision()
                || !transfer.fromOwnerUserId()
                        .equals(ownership.ownerUserId())) {
            throw new BusinessException(
                    "CALENDAR_OWNERSHIP_TRANSFER_STALE",
                    "Ownership or transfer changed before acceptance");
        }
        Instant observedNow = Instant.now(clock);
        if (!observedNow.isBefore(transfer.expiresAt())) {
            Instant expiredAt =
                    monotonic(observedNow, transfer.updatedAt());
            int expired = jdbc.update(
                    """
                    UPDATE calendar_ownership_transfer
                    SET transfer_status = 'EXPIRED',
                        transfer_revision = transfer_revision + 1,
                        canceled_at = ?, updated_at = ?
                    WHERE id = ? AND workspace_id = ?
                      AND source_created_by_user_id = ?
                      AND transfer_status = 'OFFERED'
                      AND transfer_revision = ?
                    """,
                    Timestamp.from(expiredAt),
                    Timestamp.from(expiredAt),
                    transfer.id(),
                    context.workspaceId(),
                    ownership.sourceOwnerId(),
                    transfer.revision());
            if (expired != 1) {
                throw new BusinessException(
                        "CALENDAR_OWNERSHIP_TRANSFER_STALE",
                        "Ownership transfer changed during expiry");
            }
            recordReceipt(
                    transfer.id(),
                    transfer.planId(),
                    CalendarOwnershipTransferStatus.EXPIRED,
                    transfer.revision() + 1,
                    requestHash,
                    payloadHash,
                    expiredAt,
                    context,
                    ownership.sourceOwnerId());
            insertOutbox(
                    transfer.id(),
                    transfer.planId(),
                    transfer.fromOwnerUserId(),
                    CalendarOwnershipTransferStatus.EXPIRED,
                    requestHash,
                    payloadHash,
                    expiredAt,
                    context,
                    ownership.sourceOwnerId());
            insertOutbox(
                    transfer.id(),
                    transfer.planId(),
                    transfer.toOwnerUserId(),
                    CalendarOwnershipTransferStatus.EXPIRED,
                    requestHash,
                    payloadHash,
                    expiredAt,
                    context,
                    ownership.sourceOwnerId());
            return view(transfer(transfer.id(), context, false));
        }
        requireActiveTarget(context.actorId(), context);
        Instant now = monotonic(
                observedNow,
                ownership.updatedAt(),
                transfer.updatedAt());
        int ownershipChanged = jdbc.update(
                """
                UPDATE calendar_plan_ownership
                SET owner_user_id = ?,
                    ownership_revision = ownership_revision + 1,
                    updated_at = ?
                WHERE plan_id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND owner_user_id = ?
                  AND ownership_revision = ?
                """,
                context.actorId(),
                Timestamp.from(now),
                ownership.planId(),
                context.workspaceId(),
                ownership.sourceOwnerId(),
                ownership.ownerUserId(),
                ownership.revision());
        if (ownershipChanged != 1) {
            throw ownershipConflict();
        }
        int transferChanged = jdbc.update(
                """
                UPDATE calendar_ownership_transfer
                SET transfer_status = 'ACCEPTED',
                    transfer_revision = transfer_revision + 1,
                    accepted_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND transfer_status = 'OFFERED'
                  AND transfer_revision = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                transfer.id(),
                context.workspaceId(),
                ownership.sourceOwnerId(),
                transfer.revision());
        if (transferChanged != 1) {
            throw new BusinessException(
                    "CALENDAR_OWNERSHIP_TRANSFER_STALE",
                    "Ownership transfer changed during acceptance");
        }
        recordReceipt(
                transfer.id(),
                transfer.planId(),
                CalendarOwnershipTransferStatus.ACCEPTED,
                transfer.revision() + 1,
                requestHash,
                payloadHash,
                now,
                context,
                ownership.sourceOwnerId());
        insertOutbox(
                transfer.id(),
                transfer.planId(),
                transfer.fromOwnerUserId(),
                CalendarOwnershipTransferStatus.ACCEPTED,
                requestHash,
                payloadHash,
                now,
                context,
                ownership.sourceOwnerId());
        insertOutbox(
                transfer.id(),
                transfer.planId(),
                transfer.toOwnerUserId(),
                CalendarOwnershipTransferStatus.ACCEPTED,
                requestHash,
                payloadHash,
                now,
                context,
                ownership.sourceOwnerId());
        events.publishEvent(new CalendarOwnershipTransferLifecycleEvent(
                transfer.id(),
                transfer.planId(),
                CalendarOwnershipTransferLifecycleEvent.Action.ACCEPTED,
                now));
        return view(transfer(transfer.id(), context, false));
    }

    public CalendarOwnershipTransferView cancel(
            CalendarOwnershipTransferCancellation command) {
        requireCancellation(command);
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String payloadHash = CalendarParticipationAccess.hash(
                command.transferId()
                        + "|"
                        + command.expectedTransferRevision()
                        + "|"
                        + command.expectedOwnershipRevision()
                        + "|CANCEL");
        access.lock("ownership-transfer-request|" + requestHash);
        Receipt replay = receipt(requestHash, context);
        if (replay != null) {
            requirePayload(replay.payloadHash(), payloadHash);
            return view(transfer(replay.transferId(), context, false));
        }
        Transfer identity =
                transfer(command.transferId(), context, false);
        access.lock("ownership-transfer-plan|" + identity.planId());
        Ownership ownership =
                lockOwnership(identity.planId(), context);
        Transfer transfer =
                transfer(command.transferId(), context, true);
        if (!ownership.ownerUserId().equals(context.actorId())
                || !transfer.fromOwnerUserId().equals(context.actorId())) {
            throw new SecurityException(
                    "Only the effective owner may cancel ownership transfer");
        }
        if (transfer.status()
                        != CalendarOwnershipTransferStatus.OFFERED
                || transfer.revision()
                        != command.expectedTransferRevision()
                || ownership.revision()
                        != command.expectedOwnershipRevision()
                || transfer.expectedOwnershipRevision()
                        != ownership.revision()) {
            throw new BusinessException(
                    "CALENDAR_OWNERSHIP_TRANSFER_STALE",
                    "Ownership or transfer changed before cancellation");
        }
        Instant now = monotonic(
                Instant.now(clock),
                ownership.updatedAt(),
                transfer.updatedAt());
        int changed = jdbc.update(
                """
                UPDATE calendar_ownership_transfer
                SET transfer_status = 'CANCELED',
                    transfer_revision = transfer_revision + 1,
                    canceled_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND source_created_by_user_id = ?
                  AND transfer_status = 'OFFERED'
                  AND transfer_revision = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                transfer.id(),
                context.workspaceId(),
                ownership.sourceOwnerId(),
                transfer.revision());
        if (changed != 1) {
            throw new BusinessException(
                    "CALENDAR_OWNERSHIP_TRANSFER_STALE",
                    "Ownership transfer changed during cancellation");
        }
        recordReceipt(
                transfer.id(),
                transfer.planId(),
                CalendarOwnershipTransferStatus.CANCELED,
                transfer.revision() + 1,
                requestHash,
                payloadHash,
                now,
                context,
                ownership.sourceOwnerId());
        insertOutbox(
                transfer.id(),
                transfer.planId(),
                transfer.toOwnerUserId(),
                CalendarOwnershipTransferStatus.CANCELED,
                requestHash,
                payloadHash,
                now,
                context,
                ownership.sourceOwnerId());
        events.publishEvent(new CalendarOwnershipTransferLifecycleEvent(
                transfer.id(),
                transfer.planId(),
                CalendarOwnershipTransferLifecycleEvent.Action.CANCELED,
                now));
        return view(transfer(transfer.id(), context, false));
    }

    private void expireOfferedIfNeeded(
            Ownership ownership,
            WorkspaceContext context) {
        Transfer offered = activeOffer(ownership, context);
        Instant observedNow = Instant.now(clock);
        if (offered == null
                || observedNow.isBefore(offered.expiresAt())) {
            return;
        }
        Instant now = monotonic(
                observedNow, offered.updatedAt());
        jdbc.update(
                """
                UPDATE calendar_ownership_transfer
                SET transfer_status = 'EXPIRED',
                    transfer_revision = transfer_revision + 1,
                    canceled_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND transfer_status = 'OFFERED'
                  AND transfer_revision = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                offered.id(),
                context.workspaceId(),
                offered.revision());
    }

    private Transfer activeOffer(
            Ownership ownership,
            WorkspaceContext context) {
        List<Transfer> rows = jdbc.query(
                transferSelect()
                        + """
                         AND transfer.plan_id = ?
                         AND transfer.transfer_status = 'OFFERED'
                         FOR UPDATE
                        """,
                CalendarOwnershipTransferService::transfer,
                context.workspaceId(),
                ownership.sourceOwnerId(),
                ownership.sourceOwnerId(),
                ownership.planId());
        return rows.stream().findFirst().orElse(null);
    }

    private Ownership lockOwnership(
            UUID planId,
            WorkspaceContext context) {
        List<Ownership> rows = jdbc.query(
                """
                SELECT ownership.plan_id,
                       ownership.owner_user_id,
                       ownership.ownership_revision,
                       ownership.updated_at,
                       ownership.source_created_by_user_id
                FROM calendar_plan_ownership ownership
                JOIN calendar_plan plan
                  ON plan.id = ownership.plan_id
                 AND plan.workspace_id = ownership.workspace_id
                 AND plan.created_by_user_id =
                        ownership.source_created_by_user_id
                WHERE ownership.plan_id = ?
                  AND ownership.workspace_id = ?
                  AND plan.status = 'ACTIVE'
                """,
                (row, ignored) -> new Ownership(
                        row.getObject("plan_id", UUID.class),
                        row.getObject("owner_user_id", UUID.class),
                        row.getLong("ownership_revision"),
                        row.getTimestamp("updated_at").toInstant(),
                        row.getObject(
                                "source_created_by_user_id", UUID.class)),
                planId,
                context.workspaceId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar ownership", "active plan");
        }
        return rows.getFirst();
    }

    private Transfer transfer(
            UUID transferId,
            WorkspaceContext context,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<Transfer> rows = jdbc.query(
                transferSelect() + " AND transfer.id = ?" + suffix,
                CalendarOwnershipTransferService::transfer,
                context.workspaceId(),
                null,
                null,
                transferId);
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar ownership transfer",
                    "requested transfer");
        }
        return rows.getFirst();
    }

    private static String transferSelect() {
        return """
                SELECT transfer.id, transfer.plan_id,
                       transfer.from_owner_user_id,
                       transfer.to_owner_user_id,
                       transfer.transfer_status,
                       transfer.expected_ownership_revision,
                       transfer.transfer_revision,
                       transfer.expires_at, transfer.updated_at,
                       transfer.operation_payload_hash,
                       transfer.source_created_by_user_id
                FROM calendar_ownership_transfer transfer
                WHERE transfer.workspace_id = ?
                  AND (?::uuid IS NULL
                       OR transfer.source_created_by_user_id = ?)
                """;
    }

    private static Transfer transfer(
            java.sql.ResultSet row,
            int ignored)
            throws java.sql.SQLException {
        return new Transfer(
                row.getObject("id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getObject("from_owner_user_id", UUID.class),
                row.getObject("to_owner_user_id", UUID.class),
                CalendarOwnershipTransferStatus.valueOf(
                        row.getString("transfer_status")),
                row.getLong("expected_ownership_revision"),
                row.getLong("transfer_revision"),
                row.getTimestamp("expires_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getString("operation_payload_hash"),
                row.getObject(
                        "source_created_by_user_id", UUID.class));
    }

    private void requireActiveTarget(
            UUID targetUserId,
            WorkspaceContext context) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM workspace_member member
                JOIN app_user actor ON actor.id = member.user_id
                WHERE member.workspace_id = ?
                  AND member.user_id = ?
                  AND actor.status = 'ACTIVE'
                """,
                Long.class,
                context.workspaceId(),
                targetUserId);
        if (count == null || count != 1) {
            throw new BusinessException(
                    "CALENDAR_OWNERSHIP_TARGET_NOT_ACTIVE",
                    "Ownership target must be an active workspace member");
        }
    }

    private void insertOutbox(
            UUID transferId,
            UUID planId,
            UUID recipient,
            CalendarOwnershipTransferStatus status,
            String requestHash,
            String payloadHash,
            Instant now,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        String event = "OWNERSHIP_TRANSFER_" + status;
        jdbc.update(
                """
                INSERT INTO calendar_registration_outbox (
                    id, event_type, plan_id, transfer_id,
                    recipient_user_id, operation_request_hash,
                    operation_payload_hash,
                    semantic_identity_hash, payload_text,
                    delivery_status, delivery_attempt_count,
                    next_delivery_attempt_at, delivered_at,
                    last_delivery_failure, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING', 0, ?, NULL, NULL, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                event,
                planId,
                transferId,
                recipient,
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        event + "|" + transferId + "|" + recipient),
                "Calendar ownership transfer " + status.name().toLowerCase(),
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                sourceOwnerId);
    }

    private void recordReceipt(
            UUID transferId,
            UUID planId,
            CalendarOwnershipTransferStatus status,
            long revision,
            String requestHash,
            String payloadHash,
            Instant now,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        jdbc.update(
                """
                INSERT INTO calendar_registration_request_receipt (
                    id, request_kind, plan_id, transfer_id,
                    operation_request_hash, operation_payload_hash,
                    semantic_identity_hash, result_state,
                    result_revision, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (
                    ?, 'OWNERSHIP_TRANSFER', ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                planId,
                transferId,
                requestHash,
                payloadHash,
                CalendarParticipationAccess.hash(
                        "TRANSFER|"
                                + transferId
                                + "|"
                                + status
                                + "|"
                                + revision),
                status.name(),
                revision,
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                sourceOwnerId);
    }

    private Receipt receipt(
            String requestHash,
            WorkspaceContext context) {
        List<Receipt> rows = jdbc.query(
                """
                SELECT transfer_id, operation_payload_hash
                FROM calendar_registration_request_receipt
                WHERE request_kind = 'OWNERSHIP_TRANSFER'
                  AND operation_request_hash = ?
                  AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                (row, ignored) -> new Receipt(
                        row.getObject("transfer_id", UUID.class),
                        row.getString("operation_payload_hash")),
                requestHash,
                context.workspaceId(),
                context.actorId());
        return rows.stream().findFirst().orElse(null);
    }

    private static CalendarOwnershipTransferView view(
            Transfer transfer) {
        return new CalendarOwnershipTransferView(
                transfer.id(),
                transfer.planId(),
                transfer.fromOwnerUserId(),
                transfer.toOwnerUserId(),
                transfer.status(),
                transfer.expectedOwnershipRevision(),
                transfer.revision(),
                transfer.expiresAt());
    }

    private static Instant monotonic(
            Instant candidate,
            Instant... previous) {
        Instant result = candidate;
        for (Instant value : previous) {
            if (!result.isAfter(value)) {
                result = value.plusMillis(1);
            }
        }
        return result;
    }

    private static void requireOffer(
            CalendarOwnershipTransferOffer command) {
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.planId() == null
                || command.targetOwnerUserId() == null
                || command.expectedOwnershipRevision() < 1
                || command.timeToLive() == null
                || command.timeToLive().compareTo(MIN_TTL) < 0
                || command.timeToLive().compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                    "A bounded ownership transfer offer is required");
        }
    }

    private static void requireAcceptance(
            CalendarOwnershipTransferAcceptance command) {
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.transferId() == null
                || command.expectedTransferRevision() < 1
                || command.expectedOwnershipRevision() < 1) {
            throw new IllegalArgumentException(
                    "A revision-bound ownership acceptance is required");
        }
    }

    private static void requireCancellation(
            CalendarOwnershipTransferCancellation command) {
        if (command == null
                || command.requestId() == null
                || command.requestId().isBlank()
                || command.transferId() == null
                || command.expectedTransferRevision() < 1
                || command.expectedOwnershipRevision() < 1) {
            throw new IllegalArgumentException(
                    "A revision-bound ownership cancellation is required");
        }
    }

    private static void requirePayload(
            String actual,
            String expected) {
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The ownership transfer request key was reused");
        }
    }

    private static BusinessException ownershipConflict() {
        return new BusinessException(
                "CALENDAR_OWNERSHIP_REVISION_CONFLICT",
                "Calendar ownership changed; reload before transfer");
    }

    private record Ownership(
            UUID planId,
            UUID ownerUserId,
            long revision,
            Instant updatedAt,
            UUID sourceOwnerId) {}

    private record Transfer(
            UUID id,
            UUID planId,
            UUID fromOwnerUserId,
            UUID toOwnerUserId,
            CalendarOwnershipTransferStatus status,
            long expectedOwnershipRevision,
            long revision,
            Instant expiresAt,
            Instant updatedAt,
            String payloadHash,
            UUID sourceOwnerId) {}

    private record Receipt(
            UUID transferId,
            String payloadHash) {}
}
