package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/**
 * Materializes authoritative plan signals inside the recipient's own RLS
 * context. The source owner and editors only publish immutable signals; they
 * never write another actor's personal route, receipt, or outbox.
 */
@Service
@Transactional
public class CalendarPersonalProjectionProcessor {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarPersonalProjectionProcessor(
            JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public int process(UUID actorId) {
        WorkspaceContext context = actorContext(actorId);
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)) IS NULL
                """,
                Boolean.class,
                "calendar-personal-projection|" + context.workspaceId()
                        + "|" + actorId);
        List<Signal> signals = jdbc.query(
                """
                SELECT signal.id, signal.signal_kind,
                       signal.plan_id, signal.source_node_id,
                       signal.source_revision,
                       signal.recipient_user_id,
                       signal.created_by_user_id,
                       signal.mutation_mode,
                       signal.mutation_kind,
                       signal.source_origin,
                       signal.payload_text
                FROM calendar_personal_projection_signal signal
                WHERE signal.workspace_id = ?
                  AND (
                    (signal.signal_kind = 'SOURCE_MUTATION'
                     AND signal.recipient_user_id IS NULL)
                    OR (signal.signal_kind = 'SHARE_REVOKED'
                        AND signal.recipient_user_id = ?))
                  AND NOT EXISTS (
                    SELECT 1
                    FROM calendar_authoritative_recipient_receipt receipt
                    WHERE receipt.signal_id = signal.id
                      AND receipt.workspace_id = signal.workspace_id
                      AND receipt.created_by_user_id = ?)
                ORDER BY signal.created_at, signal.id
                """,
                CalendarPersonalProjectionProcessor::signal,
                context.workspaceId(),
                actorId,
                actorId);
        int processed = 0;
        for (Signal signal : signals) {
            processed += process(signal, context) ? 1 : 0;
        }
        return processed;
    }

    private boolean process(Signal signal, WorkspaceContext context) {
        return switch (signal.kind()) {
            case "SOURCE_MUTATION" ->
                    processSourceMutation(signal, context);
            case "SHARE_REVOKED" ->
                    processShareRevoked(signal, context);
            default -> false;
        };
    }

    private boolean processSourceMutation(
            Signal signal, WorkspaceContext context) {
        if ("GENERAL_REVIEW".equals(signal.mutationMode())) {
            return processGeneralReview(signal, context);
        }
        if (!"AUTHORITATIVE_AUTO_APPLY".equals(
                signal.mutationMode())) {
            throw new IllegalStateException(
                    "Unsupported source mutation mode");
        }
        Participation participation = planParticipation(
                signal, context);
        if (participation != null) {
            if ("COMMITTED".equals(participation.state())) {
                Adoption adoption = activeAdoption(signal, context);
                if (adoption == null) {
                    return false;
                }
                Snapshot snapshot = materializeSnapshot(
                        signal,
                        context,
                        adoption,
                        participation.id(),
                        "ACTIVE");
                advanceAdoptionNodeRevision(
                        signal, context, adoption);
                applyReminderConsequences(
                        signal, context, snapshot);
                jdbc.update(
                        """
                        UPDATE calendar_adoption
                        SET revision = revision + 1, updated_at = ?
                        WHERE id = ? AND workspace_id = ?
                          AND created_by_user_id = ? AND status = 'ACTIVE'
                        """,
                        Timestamp.from(Instant.now(clock)),
                        adoption.id(),
                        context.workspaceId(),
                        context.actorId());
                record(
                        signal,
                        context,
                        adoption.id(),
                        participation.id(),
                        null,
                        snapshot.id(),
                        "AUTO_APPLIED",
                        "MANDATORY_UPDATE",
                        "AWAITING");
                return true;
            }
            if ("TENTATIVE".equals(participation.state())) {
                Adoption adoption = activeAdoption(signal, context);
                Snapshot snapshot = adoption == null
                        ? null
                        : materializeSnapshot(
                                signal,
                                context,
                                adoption,
                                participation.id(),
                                "REVIEW_REQUIRED");
                record(
                        signal,
                        context,
                        adoption == null ? null : adoption.id(),
                        participation.id(),
                        null,
                        snapshot == null ? null : snapshot.id(),
                        "REVIEW_REQUIRED",
                        "REVIEW_REQUIRED_UPDATE",
                        "NOT_REQUIRED");
                return true;
            }
            recordReceipt(
                    signal,
                    context,
                    null,
                    participation.id(),
                    null,
                    null,
                    "SUPPRESSED",
                    "NOT_REQUIRED");
            return true;
        }

        Subscription subscription = watchingSubscription(
                signal, context);
        if (subscription == null) {
            recordReceipt(
                    signal,
                    context,
                    null,
                    null,
                    null,
                    null,
                    "NO_ACTION",
                    "NOT_REQUIRED");
            return true;
        }
        record(
                signal,
                context,
                null,
                null,
                subscription.id(),
                null,
                "ROUTINE_UPDATE",
                "ROUTINE_UPDATE",
                "NOT_REQUIRED");
        return true;
    }

    private boolean processGeneralReview(
            Signal signal, WorkspaceContext context) {
        Participation participation =
                planParticipation(signal, context);
        if (participation != null) {
            if ("COMMITTED".equals(participation.state())
                    || "TENTATIVE".equals(participation.state())) {
                Adoption adoption = activeAdoption(signal, context);
                Snapshot snapshot = adoption == null
                        ? null
                        : materializeSnapshot(
                                signal,
                                context,
                                adoption,
                                participation.id(),
                                "REVIEW_REQUIRED");
                record(
                        signal,
                        context,
                        adoption == null ? null : adoption.id(),
                        participation.id(),
                        null,
                        snapshot == null ? null : snapshot.id(),
                        "REVIEW_REQUIRED",
                        "REVIEW_REQUIRED_UPDATE",
                        "NOT_REQUIRED");
                return true;
            }
            recordReceipt(
                    signal,
                    context,
                    null,
                    participation.id(),
                    null,
                    null,
                    "SUPPRESSED",
                    "NOT_REQUIRED");
            return true;
        }
        Subscription subscription =
                watchingSubscription(signal, context);
        if (subscription == null) {
            recordReceipt(
                    signal,
                    context,
                    null,
                    null,
                    null,
                    null,
                    "NO_ACTION",
                    "NOT_REQUIRED");
            return true;
        }
        record(
                signal,
                context,
                null,
                null,
                subscription.id(),
                null,
                "ROUTINE_UPDATE",
                "ROUTINE_UPDATE",
                "NOT_REQUIRED");
        return true;
    }

    private boolean processShareRevoked(
            Signal signal, WorkspaceContext context) {
        boolean additiveAccessRemains = Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1 FROM calendar_share share
                            WHERE share.plan_id = ?
                              AND share.workspace_id = ?
                              AND share.grantee_user_id = ?
                              AND share.status = 'ACTIVE')
                        """,
                        Boolean.class,
                        signal.planId(),
                        context.workspaceId(),
                        context.actorId()));
        Snapshot retained = null;
        Adoption adoption = activeAdoption(signal, context);
        if (!additiveAccessRemains) {
            if (adoption != null) {
                retained = retainLatestSnapshot(
                        signal, context, adoption);
            }
            unsubscribeRoutine(signal, context);
        } else {
            recordReceipt(
                    signal,
                    context,
                    null,
                    null,
                    null,
                    null,
                    "ACCESS_RETAINED",
                    "NOT_REQUIRED");
            return true;
        }
        record(
                signal,
                context,
                adoption == null ? null : adoption.id(),
                null,
                null,
                retained == null ? null : retained.id(),
                "SOURCE_ACCESS_REVOKED",
                "SHARE_ACCESS_REVOKED",
                "NOT_REQUIRED");
        return true;
    }

    private Snapshot materializeSnapshot(
            Signal signal,
            WorkspaceContext context,
            Adoption adoption,
            UUID participationId,
            String status) {
        supersedeLatest(adoption, context);
        long revision = nextProjectionRevision(adoption, context);
        UUID snapshotId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_snapshot (
                    id, adoption_id, participation_id, plan_id,
                    plan_title, placement_kind, timed_start, timed_end,
                    zone_id, all_day_start, all_day_end_exclusive,
                    source_signal_id, source_signal_kind,
                    source_signal_owner_user_id,
                    source_signal_recipient_user_id,
                    projection_revision, source_plan_revision,
                    projection_status, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                SELECT ?, ?, ?, plan.id,
                       plan.title, plan.placement_kind,
                       plan.timed_start, plan.timed_end, plan.zone_id,
                       plan.all_day_start, plan.all_day_end_exclusive,
                       ?, ?, ?, NULL, ?, plan.version + 1,
                       ?, ?, ?, ?, ?
                FROM calendar_plan plan
                WHERE plan.id = ? AND plan.workspace_id = ?
                  AND plan.created_by_user_id = ?
                """,
                snapshotId,
                adoption.id(),
                participationId,
                signal.id(),
                signal.kind(),
                signal.ownerId(),
                revision,
                status,
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId(),
                signal.planId(),
                context.workspaceId(),
                signal.ownerId());
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_node (
                    snapshot_id, adoption_id, plan_id,
                    source_node_id, activity_id, node_key, label,
                    expression_kind, absolute_time, offset_seconds,
                    base_node_key, resolved_time,
                    criticality, adjustability,
                    location_label, latitude, longitude,
                    cancellation_status, canceled_at,
                    source_node_revision, created_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                SELECT ?, selected.adoption_id, selected.plan_id,
                       node.id, node.activity_id, node.node_key, node.label,
                       node.expression_kind, node.absolute_time,
                       node.offset_seconds, node.base_node_key,
                       node.resolved_time,
                       node.criticality, node.adjustability,
                       node.location_label, node.latitude, node.longitude,
                       node.cancellation_status, node.canceled_at,
                       node.revision, ?, selected.workspace_id,
                       selected.created_by_user_id,
                       selected.source_created_by_user_id
                FROM calendar_adoption_node selected
                JOIN calendar_time_node node
                  ON node.id = selected.node_id
                 AND node.plan_id = selected.plan_id
                 AND node.workspace_id = selected.workspace_id
                 AND node.created_by_user_id =
                     selected.source_created_by_user_id
                WHERE selected.adoption_id = ?
                  AND selected.workspace_id = ?
                  AND selected.created_by_user_id = ?
                """,
                snapshotId,
                Timestamp.from(now),
                adoption.id(),
                context.workspaceId(),
                context.actorId());
        return new Snapshot(snapshotId, revision);
    }

    private Snapshot retainLatestSnapshot(
            Signal signal,
            WorkspaceContext context,
            Adoption adoption) {
        Snapshot latest = latestSnapshot(adoption, context);
        if (latest == null) {
            return null;
        }
        supersedeLatest(adoption, context);
        long revision = nextProjectionRevision(adoption, context);
        UUID retainedId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_snapshot (
                    id, adoption_id, participation_id, plan_id,
                    plan_title, placement_kind, timed_start, timed_end,
                    zone_id, all_day_start, all_day_end_exclusive,
                    source_signal_id, source_signal_kind,
                    source_signal_owner_user_id,
                    source_signal_recipient_user_id,
                    projection_revision, source_plan_revision,
                    projection_status, created_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                SELECT ?, adoption_id, NULL, plan_id,
                       plan_title, placement_kind, timed_start, timed_end,
                       zone_id, all_day_start, all_day_end_exclusive,
                       ?, ?, ?, ?,
                       ?, source_plan_revision,
                       'RETAINED_NO_SOURCE_ACCESS', ?, workspace_id,
                       created_by_user_id, source_created_by_user_id
                FROM calendar_personal_projection_snapshot
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                retainedId,
                signal.id(),
                signal.kind(),
                signal.ownerId(),
                context.actorId(),
                revision,
                Timestamp.from(now),
                latest.id(),
                context.workspaceId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_node (
                    snapshot_id, adoption_id, plan_id,
                    source_node_id, activity_id, node_key, label,
                    expression_kind, absolute_time, offset_seconds,
                    base_node_key, resolved_time,
                    criticality, adjustability,
                    location_label, latitude, longitude,
                    cancellation_status, canceled_at,
                    source_node_revision, created_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                SELECT ?, adoption_id, plan_id, source_node_id,
                       activity_id, node_key, label, expression_kind,
                       absolute_time, offset_seconds, base_node_key,
                       resolved_time,
                       criticality, adjustability, location_label,
                       latitude, longitude, cancellation_status,
                       canceled_at, source_node_revision, ?,
                       workspace_id, created_by_user_id,
                       source_created_by_user_id
                FROM calendar_personal_projection_node
                WHERE snapshot_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                retainedId,
                Timestamp.from(now),
                latest.id(),
                context.workspaceId(),
                context.actorId());
        return new Snapshot(retainedId, revision);
    }

    private void record(
            Signal signal,
            WorkspaceContext context,
            UUID adoptionId,
            UUID participationId,
            UUID subscriptionId,
            UUID snapshotId,
            String disposition,
            String eventType,
            String acknowledgement) {
        UUID receiptId = recordReceipt(
                signal,
                context,
                adoptionId,
                participationId,
                subscriptionId,
                snapshotId,
                disposition,
                acknowledgement);
        recordOutbox(
                signal, context, receiptId, disposition, eventType);
    }

    private UUID recordReceipt(
            Signal signal,
            WorkspaceContext context,
            UUID adoptionId,
            UUID participationId,
            UUID subscriptionId,
            UUID snapshotId,
            String disposition,
            String acknowledgement) {
        UUID receiptId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        boolean deliveryRequired = switch (disposition) {
            case "AUTO_APPLIED",
                    "REVIEW_REQUIRED",
                    "ROUTINE_UPDATE",
                    "SOURCE_ACCESS_REVOKED" -> true;
            default -> false;
        };
        String deliveryStatus =
                deliveryRequired ? "PENDING" : "NOT_REQUIRED";
        Timestamp nextDeliveryAttemptAt =
                deliveryRequired ? Timestamp.from(now) : null;
        jdbc.update(
                """
                INSERT INTO calendar_authoritative_recipient_receipt (
                    id, signal_id, signal_kind,
                    source_signal_owner_user_id,
                    signal_recipient_user_id, adoption_id,
                    participation_id, routine_subscription_id,
                    personal_snapshot_id, plan_id, disposition,
                    delivery_status, delivery_attempt_count,
                    next_delivery_attempt_at, delivered_at,
                    last_delivery_failure, acknowledgement_status,
                    acknowledged_at, receipt_revision,
                    created_at, updated_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, 0, ?, NULL, NULL, ?, NULL, 1,
                        ?, ?, ?, ?, ?)
                """,
                receiptId,
                signal.id(),
                signal.kind(),
                signal.ownerId(),
                signal.recipientId(),
                adoptionId,
                participationId,
                subscriptionId,
                snapshotId,
                signal.planId(),
                disposition,
                deliveryStatus,
                nextDeliveryAttemptAt,
                acknowledgement,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
        return receiptId;
    }

    private void recordOutbox(
            Signal signal,
            WorkspaceContext context,
            UUID receiptId,
            String disposition,
            String eventType) {
        Instant now = Instant.now(clock);
        String identity = signal.id() + "|" + context.actorId()
                + "|" + eventType;
        String payload = "signal=" + signal.id()
                + "|disposition=" + disposition
                + "|sourceRevision=" + signal.sourceRevision();
        jdbc.update(
                """
                INSERT INTO calendar_participation_outbox (
                    id, event_type, signal_id, signal_kind,
                    source_signal_owner_user_id,
                    signal_recipient_user_id, receipt_id,
                    suppression_id, plan_id, source_revision,
                    operation_request_hash, operation_payload_hash,
                    semantic_identity_hash, payload_text,
                    delivery_status, delivery_attempt_count,
                    next_delivery_attempt_at, delivered_at,
                    last_delivery_failure, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?,
                        'PENDING', 0, ?, NULL, NULL, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                eventType,
                signal.id(),
                signal.kind(),
                signal.ownerId(),
                signal.recipientId(),
                receiptId,
                signal.planId(),
                signal.sourceRevision(),
                hash("request|" + identity),
                hash(payload),
                hash("semantic|" + identity),
                payload,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
    }

    private Participation planParticipation(
            Signal signal, WorkspaceContext context) {
        List<Participation> rows = jdbc.query(
                """
                SELECT id, participation_state
                FROM calendar_participation
                WHERE plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND (
                    target_scope = 'PLAN'
                    OR (
                      target_scope = 'ACTIVITY'
                      AND EXISTS (
                        SELECT 1
                        FROM calendar_time_node node
                        WHERE node.id = ?
                          AND node.plan_id =
                              calendar_participation.plan_id
                          AND node.workspace_id =
                              calendar_participation.workspace_id
                          AND node.created_by_user_id =
                              calendar_participation
                                  .source_created_by_user_id
                          AND node.activity_id =
                              calendar_participation.activity_id)))
                ORDER BY
                    CASE target_scope
                        WHEN 'ACTIVITY' THEN 0
                        ELSE 1
                    END,
                    id
                LIMIT 1
                """,
                (row, ignored) -> new Participation(
                        row.getObject("id", UUID.class),
                        row.getString("participation_state")),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId(),
                signal.sourceNodeId());
        return rows.stream().findFirst().orElse(null);
    }

    private Adoption activeAdoption(
            Signal signal, WorkspaceContext context) {
        List<Adoption> rows = jdbc.query(
                """
                SELECT id
                FROM calendar_adoption adoption
                WHERE adoption.plan_id = ?
                  AND adoption.status = 'ACTIVE'
                  AND adoption.workspace_id = ?
                  AND adoption.created_by_user_id = ?
                  AND adoption.source_created_by_user_id = ?
                  AND EXISTS (
                    SELECT 1
                    FROM calendar_adoption_node selected
                    WHERE selected.adoption_id = adoption.id
                      AND selected.plan_id = adoption.plan_id
                      AND selected.workspace_id =
                          adoption.workspace_id
                      AND selected.created_by_user_id =
                          adoption.created_by_user_id
                      AND selected.source_created_by_user_id =
                          adoption.source_created_by_user_id
                      AND (
                        ?::uuid IS NULL
                        OR selected.node_id = ?))
                FOR UPDATE
                """,
                (row, ignored) ->
                        new Adoption(row.getObject("id", UUID.class)),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId(),
                signal.sourceNodeId(),
                signal.sourceNodeId());
        return rows.stream().findFirst().orElse(null);
    }

    private Subscription watchingSubscription(
            Signal signal, WorkspaceContext context) {
        List<Subscription> rows = jdbc.query(
                """
                SELECT id
                FROM calendar_routine_subscription
                WHERE plan_id = ? AND subscription_state = 'WATCHING'
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND (
                    target_scope = 'PLAN'
                    OR (
                      target_scope = 'ACTIVITY'
                      AND EXISTS (
                        SELECT 1
                        FROM calendar_time_node node
                        WHERE node.id = ?
                          AND node.plan_id =
                              calendar_routine_subscription.plan_id
                          AND node.workspace_id =
                              calendar_routine_subscription.workspace_id
                          AND node.created_by_user_id =
                              calendar_routine_subscription
                                  .source_created_by_user_id
                          AND node.activity_id =
                              calendar_routine_subscription.activity_id)))
                ORDER BY
                    CASE target_scope
                        WHEN 'ACTIVITY' THEN 0
                        ELSE 1
                    END,
                    id
                LIMIT 1
                """,
                (row, ignored) ->
                        new Subscription(row.getObject("id", UUID.class)),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId(),
                signal.sourceNodeId());
        return rows.stream().findFirst().orElse(null);
    }

    private void advanceAdoptionNodeRevision(
            Signal signal,
            WorkspaceContext context,
            Adoption adoption) {
        jdbc.update(
                """
                UPDATE calendar_adoption_node
                SET node_revision = ?
                WHERE adoption_id = ? AND node_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND node_revision < ?
                """,
                signal.sourceRevision(),
                adoption.id(),
                signal.sourceNodeId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId(),
                signal.sourceRevision());
    }

    private void applyReminderConsequences(
            Signal signal,
            WorkspaceContext context,
            Snapshot snapshot) {
        if ("NODE_LOCATION".equals(signal.mutationKind())) {
            return;
        }
        List<ProjectedNode> nodes = jdbc.query(
                """
                SELECT resolved_time, cancellation_status
                FROM calendar_personal_projection_node
                WHERE snapshot_id = ? AND source_node_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                """,
                (row, ignored) -> new ProjectedNode(
                        row.getTimestamp("resolved_time").toInstant(),
                        row.getString("cancellation_status")),
                snapshot.id(),
                signal.sourceNodeId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
        if (nodes.size() != 1) {
            return;
        }
        ProjectedNode node = nodes.getFirst();
        jdbc.queryForList(
                """
                SELECT id
                FROM calendar_reminder_rule
                WHERE node_id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND status = 'ACTIVE'
                ORDER BY id
                FOR UPDATE
                """,
                UUID.class,
                signal.sourceNodeId(),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
        jdbc.queryForList(
                """
                SELECT id
                FROM calendar_reminder_occurrence
                WHERE node_id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND status IN ('PENDING', 'ENQUEUED')
                ORDER BY id
                FOR UPDATE
                """,
                UUID.class,
                signal.sourceNodeId(),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
        Instant now = Instant.now(clock);
        Timestamp changedAt = Timestamp.from(now);
        jdbc.update(
                """
                UPDATE calendar_reminder_occurrence
                SET status = 'CANCELED', version = version + 1,
                    updated_at = ?
                WHERE node_id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND status IN ('PENDING', 'ENQUEUED')
                """,
                changedAt,
                signal.sourceNodeId(),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
        if ("NODE_CANCELLATION".equals(signal.mutationKind())
                || "CANCELED".equals(node.cancellationStatus())) {
            jdbc.update(
                    """
                    UPDATE calendar_reminder_rule
                    SET status = 'CANCELED',
                        revision = revision + 1,
                        version = version + 1, updated_at = ?
                    WHERE node_id = ? AND plan_id = ?
                      AND workspace_id = ? AND created_by_user_id = ?
                      AND source_created_by_user_id = ?
                      AND status = 'ACTIVE'
                    """,
                    changedAt,
                    signal.sourceNodeId(),
                    signal.planId(),
                    context.workspaceId(),
                    context.actorId(),
                    signal.ownerId());
            return;
        }
        if (!"NODE_TIME".equals(signal.mutationKind())) {
            return;
        }
        jdbc.update(
                """
                UPDATE calendar_reminder_rule
                SET status = 'REVIEW_REQUIRED',
                    revision = revision + 1,
                    version = version + 1, updated_at = ?
                WHERE node_id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                  AND status = 'ACTIVE' AND rule_kind = 'ABSOLUTE'
                """,
                changedAt,
                signal.sourceNodeId(),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
        jdbc.update(
                """
                INSERT INTO calendar_reminder_occurrence (
                    id, rule_id, plan_id, node_id, node_revision,
                    rule_revision, sequence_number, scheduled_at,
                    status, version, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                SELECT gen_random_uuid(), rule_row.id,
                       rule_row.plan_id, rule_row.node_id, ?,
                       rule_row.revision, 0,
                       ?::timestamptz + make_interval(
                           secs =>
                               rule_row.offset_seconds::double precision),
                       'PENDING', 0, ?, ?, rule_row.workspace_id,
                       rule_row.created_by_user_id,
                       rule_row.source_created_by_user_id
                FROM calendar_reminder_rule rule_row
                WHERE rule_row.node_id = ? AND rule_row.plan_id = ?
                  AND rule_row.workspace_id = ?
                  AND rule_row.created_by_user_id = ?
                  AND rule_row.source_created_by_user_id = ?
                  AND rule_row.status = 'ACTIVE'
                  AND rule_row.owner_kind = 'PERSONAL'
                  AND rule_row.rule_kind = 'RELATIVE'
                """,
                signal.sourceRevision(),
                Timestamp.from(node.resolvedTime()),
                changedAt,
                changedAt,
                signal.sourceNodeId(),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
    }

    private void unsubscribeRoutine(
            Signal signal, WorkspaceContext context) {
        Instant now = Instant.now(clock);
        String requestHash = hash(
                "share-revoke-routine|" + signal.id());
        jdbc.update(
                """
                UPDATE calendar_routine_subscription
                SET subscription_state = 'UNSUBSCRIBED',
                    subscription_revision = subscription_revision + 1,
                    source_revision = GREATEST(source_revision, ?),
                    operation_request_hash = ?,
                    operation_payload_hash = ?,
                    updated_at = ?, unsubscribed_at = ?
                WHERE plan_id = ? AND subscription_state = 'WATCHING'
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                """,
                signal.sourceRevision(),
                requestHash,
                hash(requestHash + "|UNSUBSCRIBED"),
                Timestamp.from(now),
                Timestamp.from(now),
                signal.planId(),
                context.workspaceId(),
                context.actorId(),
                signal.ownerId());
    }

    private void supersedeLatest(
            Adoption adoption, WorkspaceContext context) {
        jdbc.update(
                """
                UPDATE calendar_personal_projection_snapshot
                SET projection_status = 'SUPERSEDED'
                WHERE adoption_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND projection_status <> 'SUPERSEDED'
                """,
                adoption.id(),
                context.workspaceId(),
                context.actorId());
    }

    private Snapshot latestSnapshot(
            Adoption adoption, WorkspaceContext context) {
        List<Snapshot> rows = jdbc.query(
                """
                SELECT id, projection_revision
                FROM calendar_personal_projection_snapshot
                WHERE adoption_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND projection_status <> 'SUPERSEDED'
                ORDER BY projection_revision DESC
                LIMIT 1
                """,
                (row, ignored) -> new Snapshot(
                        row.getObject("id", UUID.class),
                        row.getLong("projection_revision")),
                adoption.id(),
                context.workspaceId(),
                context.actorId());
        return rows.stream().findFirst().orElse(null);
    }

    private long nextProjectionRevision(
            Adoption adoption, WorkspaceContext context) {
        Long revision = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(projection_revision), 0) + 1
                FROM calendar_personal_projection_snapshot
                WHERE adoption_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                Long.class,
                adoption.id(),
                context.workspaceId(),
                context.actorId());
        return revision == null ? 1 : revision;
    }

    private static Signal signal(ResultSet row, int ignored)
            throws SQLException {
        return new Signal(
                row.getObject("id", UUID.class),
                row.getString("signal_kind"),
                row.getObject("plan_id", UUID.class),
                row.getObject("source_node_id", UUID.class),
                row.getLong("source_revision"),
                row.getObject("recipient_user_id", UUID.class),
                row.getObject("created_by_user_id", UUID.class),
                row.getString("mutation_mode"),
                row.getString("mutation_kind"),
                row.getString("source_origin"),
                row.getString("payload_text"));
    }

    private static WorkspaceContext actorContext(UUID actorId) {
        if (actorId == null) {
            throw new IllegalArgumentException(
                    "Personal projection actor is required");
        }
        WorkspaceContext context =
                WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()
                || !actorId.equals(context.actorId())) {
            throw new SecurityException(
                    "Personal projections may only be processed by their actor");
        }
        return context;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    private record Signal(
            UUID id,
            String kind,
            UUID planId,
            UUID sourceNodeId,
            long sourceRevision,
            UUID recipientId,
            UUID ownerId,
            String mutationMode,
            String mutationKind,
            String sourceOrigin,
            String payload) {}

    private record Participation(UUID id, String state) {}

    private record Adoption(UUID id) {}

    private record Subscription(UUID id) {}

    private record Snapshot(UUID id, long revision) {}

    private record ProjectedNode(
            Instant resolvedTime, String cancellationStatus) {}
}
