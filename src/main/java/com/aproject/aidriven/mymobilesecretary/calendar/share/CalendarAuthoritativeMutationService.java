package com.aproject.aidriven.mymobilesecretary.calendar.share;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
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
public class CalendarAuthoritativeMutationService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarAuthoritativeMutationService(
            JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarAuthoritativeMutationResult reviseAbsoluteTime(
            CalendarAuthoritativeTimeChange change) {
        String afterValue = jdbc.queryForObject(
                "SELECT (?::timestamptz)::text",
                String.class,
                Timestamp.from(change.absoluteTime()));
        return execute(
                change.requestKey(),
                change.capabilityId(),
                change.nodeId(),
                change.expectedNodeRevision(),
                change.reason(),
                change.source(),
                CalendarAuthoritativeMutationResult.Kind.NODE_TIME,
                afterValue,
                (node, now) -> {
                    if (!"ABSOLUTE".equals(node.expressionKind())) {
                        throw new BusinessException(
                                "CALENDAR_AUTHORITATIVE_TIME_REQUIRES_ABSOLUTE",
                                "Only an absolute calendar node can change authoritative time");
                    }
                    return jdbc.update(
                            """
                            UPDATE calendar_time_node
                            SET absolute_time = ?, resolved_time = ?,
                                revision = revision + 1,
                                version = version + 1, updated_at = ?
                            WHERE id = ? AND plan_id = ?
                              AND workspace_id = ?
                              AND created_by_user_id = ?
                              AND revision = ?
                            """,
                            Timestamp.from(change.absoluteTime()),
                            Timestamp.from(change.absoluteTime()),
                            Timestamp.from(now),
                            change.nodeId(),
                            node.planId(),
                            node.workspaceId(),
                            node.ownerId(),
                            change.expectedNodeRevision());
                });
    }

    public CalendarAuthoritativeMutationResult reviseLocation(
            CalendarAuthoritativeLocationChange change) {
        CalendarLocation location = change.location();
        String afterValue = jdbc.queryForObject(
                """
                SELECT concat_ws(
                    '|', ?, (?::double precision)::text,
                    (?::double precision)::text)
                """,
                String.class,
                location.label(),
                location.latitude(),
                location.longitude());
        return execute(
                change.requestKey(),
                change.capabilityId(),
                change.nodeId(),
                change.expectedNodeRevision(),
                change.reason(),
                change.source(),
                CalendarAuthoritativeMutationResult.Kind.NODE_LOCATION,
                afterValue,
                (node, now) -> jdbc.update(
                        """
                        UPDATE calendar_time_node
                        SET location_label = ?, latitude = ?, longitude = ?,
                            revision = revision + 1,
                            version = version + 1, updated_at = ?
                        WHERE id = ? AND plan_id = ?
                          AND workspace_id = ?
                          AND created_by_user_id = ?
                          AND revision = ?
                        """,
                        location.label(),
                        location.latitude(),
                        location.longitude(),
                        Timestamp.from(now),
                        change.nodeId(),
                        node.planId(),
                        node.workspaceId(),
                        node.ownerId(),
                        change.expectedNodeRevision()));
    }

    public CalendarAuthoritativeMutationResult cancelNode(
            CalendarAuthoritativeCancellationChange change) {
        return execute(
                change.requestKey(),
                change.capabilityId(),
                change.nodeId(),
                change.expectedNodeRevision(),
                change.reason(),
                change.source(),
                CalendarAuthoritativeMutationResult.Kind
                        .NODE_CANCELLATION,
                "CANCELED",
                (node, now) -> {
                    if (!"ACTIVE".equals(node.cancellationStatus())) {
                        throw new BusinessException(
                                "CALENDAR_NODE_ALREADY_CANCELED",
                                "Calendar node is already canceled");
                    }
                    return jdbc.update(
                            """
                            UPDATE calendar_time_node
                            SET cancellation_status = 'CANCELED',
                                canceled_at = ?,
                                revision = revision + 1,
                                version = version + 1,
                                updated_at = ?
                            WHERE id = ? AND plan_id = ?
                              AND workspace_id = ?
                              AND created_by_user_id = ?
                              AND revision = ?
                              AND cancellation_status = 'ACTIVE'
                            """,
                            Timestamp.from(now),
                            Timestamp.from(now),
                            change.nodeId(),
                            node.planId(),
                            node.workspaceId(),
                            node.ownerId(),
                            change.expectedNodeRevision());
                });
    }

    private CalendarAuthoritativeMutationResult execute(
            String requestKey,
            UUID capabilityId,
            UUID nodeId,
            long expectedRevision,
            String reason,
            String source,
            CalendarAuthoritativeMutationResult.Kind kind,
            String afterValue,
            NodeUpdater updater) {
        WorkspaceContext context = CalendarShareService.context();
        String requestHash = CalendarShareService.hash(requestKey);
        lockRequest(requestHash);
        lockRequest(CalendarShareService.hash(
                capabilityId
                        + "|"
                        + nodeId
                        + "|"
                        + expectedRevision
                        + "|"
                        + kind
                        + "|"
                        + afterValue
                        + "|"
                        + reason
                        + "|"
                        + source));
        lockRequest("calendar-source-node|" + nodeId);
        CalendarAuthoritativeMutationResult replay = replay(
                requestHash,
                capabilityId,
                nodeId,
                expectedRevision,
                reason,
                source,
                kind,
                afterValue,
                context);
        if (replay != null) {
            return replay;
        }

        Capability capability =
                findCapability(capabilityId, context, false);
        lockPlan(capability.planId(), capability.ownerId(), context);
        lockShare(capability.shareId(), capability.ownerId(), context);
        capability = findCapability(capabilityId, context, true);
        NodeState node = lockNode(
                nodeId,
                capability.planId(),
                capability.ownerId(),
                context);
        requireAuthorization(capability, node, context);
        if (!"ACTIVE".equals(node.cancellationStatus())) {
            throw new BusinessException(
                    "CALENDAR_NODE_ALREADY_CANCELED",
                    "A canceled calendar node cannot be revised");
        }
        if (node.revision() != expectedRevision) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_NODE_REVISION_CONFLICT",
                    "Calendar node changed; reload before authoritative mutation");
        }

        UUID mutationId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        int inserted = jdbc.update(
                """
                INSERT INTO calendar_authoritative_mutation_audit (
                    id, mutation_id, capability_id, share_id, plan_id,
                    node_id, mutation_kind, reason, source,
                    before_value, after_value,
                    previous_target_revision, current_target_revision,
                    operation_request_hash, occurred_at, workspace_id,
                    created_by_user_id, editor_user_id)
                SELECT ?, ?, ?, ?, node_row.plan_id, node_row.id, ?, ?, ?,
                       CASE
                            WHEN ? = 'NODE_TIME'
                                THEN node_row.absolute_time::text
                            WHEN ? = 'NODE_CANCELLATION'
                                THEN node_row.cancellation_status
                            ELSE concat_ws(
                                '|',
                                COALESCE(node_row.location_label, ''),
                                COALESCE(node_row.latitude::text, ''),
                                COALESCE(node_row.longitude::text, ''))
                       END,
                       ?, node_row.revision, node_row.revision + 1,
                       ?, ?, node_row.workspace_id,
                       node_row.created_by_user_id, ?
                FROM calendar_time_node node_row
                WHERE node_row.id = ? AND node_row.plan_id = ?
                  AND node_row.workspace_id = ?
                  AND node_row.created_by_user_id = ?
                  AND node_row.revision = ?
                """,
                UUID.randomUUID(),
                mutationId,
                capability.id(),
                capability.shareId(),
                kind.name(),
                reason,
                source,
                kind.name(),
                kind.name(),
                afterValue,
                requestHash,
                Timestamp.from(now),
                context.actorId(),
                node.id(),
                node.planId(),
                context.workspaceId(),
                node.ownerId(),
                expectedRevision);
        if (inserted != 1) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_NODE_REVISION_CONFLICT",
                    "Calendar node changed; reload before authoritative mutation");
        }
        jdbc.queryForObject(
                """
                SELECT set_config(
                    'app.calendar_authoritative_mutation_id', ?, true)
                """,
                String.class,
                mutationId.toString());
        Instant mutationTime =
                now.isBefore(node.updatedAt()) ? node.updatedAt() : now;
        if (updater.update(node, mutationTime) != 1) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_NODE_REVISION_CONFLICT",
                    "Calendar node changed; reload before authoritative mutation");
        }
        applyReminderConsequences(
                kind,
                node,
                expectedRevision + 1,
                afterValue,
                mutationTime);
        UUID sourceOutboxId = UUID.randomUUID();
        String outboxPayload = payload(
                mutationId,
                capability.id(),
                node.id(),
                kind,
                expectedRevision + 1,
                reason,
                source);
        jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id, created_by_user_id,
                    authoritative_mutation_id)
                VALUES (?, ?, 'AUTHORITATIVE_MUTATION', ?, NULL, ?, ?,
                        'PENDING', ?, ?, ?, ?)
                """,
                sourceOutboxId,
                CalendarShareService.hash(
                        "AUTHORITATIVE_MUTATION|" + mutationId),
                capability.shareId(),
                context.actorId(),
                outboxPayload,
                Timestamp.from(now),
                context.workspaceId(),
                capability.ownerId(),
                mutationId);
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_signal (
                    id, signal_kind, source_outbox_id,
                    authoritative_mutation_id, share_id, plan_id,
                    source_node_id, source_revision, recipient_user_id,
                    mutation_mode, mutation_kind, source_origin,
                    payload_text, created_at, workspace_id,
                    created_by_user_id)
                VALUES (?, 'SOURCE_MUTATION', ?, ?, NULL, ?, ?, ?,
                        NULL, 'AUTHORITATIVE_AUTO_APPLY', ?,
                        'AUTHORIZED_EDITOR', ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                sourceOutboxId,
                mutationId,
                node.planId(),
                node.id(),
                expectedRevision + 1,
                kind.name(),
                outboxPayload,
                Timestamp.from(now),
                context.workspaceId(),
                capability.ownerId());
        return new CalendarAuthoritativeMutationResult(
                mutationId, node.id(), kind, expectedRevision + 1);
    }

    private void applyReminderConsequences(
            CalendarAuthoritativeMutationResult.Kind kind,
            NodeState node,
            long currentNodeRevision,
            String afterValue,
            Instant now) {
        if (kind == CalendarAuthoritativeMutationResult.Kind.NODE_LOCATION) {
            return;
        }
        jdbc.queryForList(
                """
                SELECT id
                FROM calendar_reminder_rule
                WHERE node_id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                ORDER BY id
                FOR UPDATE
                """,
                UUID.class,
                node.id(),
                node.planId(),
                node.workspaceId(),
                node.ownerId());
        jdbc.queryForList(
                """
                SELECT id
                FROM calendar_reminder_occurrence
                WHERE node_id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND status IN ('PENDING', 'ENQUEUED')
                ORDER BY id
                FOR UPDATE
                """,
                UUID.class,
                node.id(),
                node.planId(),
                node.workspaceId(),
                node.ownerId());
        Timestamp mutationTimestamp = Timestamp.from(now);
        if (kind
                == CalendarAuthoritativeMutationResult.Kind
                        .NODE_CANCELLATION) {
            jdbc.update(
                    """
                    UPDATE calendar_reminder_occurrence
                    SET status = 'CANCELED', version = version + 1,
                        updated_at = ?
                    WHERE node_id = ? AND plan_id = ?
                      AND workspace_id = ? AND created_by_user_id = ?
                      AND status IN ('PENDING', 'ENQUEUED')
                    """,
                    mutationTimestamp,
                    node.id(),
                    node.planId(),
                    node.workspaceId(),
                    node.ownerId());
            jdbc.update(
                    """
                    UPDATE calendar_reminder_rule
                    SET status = 'CANCELED', revision = revision + 1,
                        version = version + 1, updated_at = ?
                    WHERE node_id = ? AND plan_id = ?
                      AND workspace_id = ? AND created_by_user_id = ?
                      AND status = 'ACTIVE'
                    """,
                    mutationTimestamp,
                    node.id(),
                    node.planId(),
                    node.workspaceId(),
                    node.ownerId());
            return;
        }
        jdbc.update(
                """
                UPDATE calendar_reminder_occurrence
                SET status = 'CANCELED', version = version + 1,
                    updated_at = ?
                WHERE node_id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND status IN ('PENDING', 'ENQUEUED')
                """,
                mutationTimestamp,
                node.id(),
                node.planId(),
                node.workspaceId(),
                node.ownerId());
        jdbc.update(
                """
                UPDATE calendar_reminder_rule
                SET status = 'REVIEW_REQUIRED',
                    revision = revision + 1,
                    version = version + 1, updated_at = ?
                WHERE node_id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE' AND rule_kind = 'ABSOLUTE'
                """,
                mutationTimestamp,
                node.id(),
                node.planId(),
                node.workspaceId(),
                node.ownerId());
        jdbc.update(
                """
                INSERT INTO calendar_reminder_occurrence (
                    id, rule_id, plan_id, node_id, node_revision,
                    rule_revision, sequence_number, scheduled_at,
                    status, version, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                SELECT gen_random_uuid(), rule_row.id, rule_row.plan_id,
                       rule_row.node_id, ?, rule_row.revision, 0,
                       ?::timestamptz + make_interval(
                           secs =>
                               rule_row.offset_seconds::double precision),
                       'PENDING', 0, ?, ?,
                       rule_row.workspace_id,
                       rule_row.created_by_user_id,
                       rule_row.source_created_by_user_id
                FROM calendar_reminder_rule rule_row
                WHERE rule_row.node_id = ? AND rule_row.plan_id = ?
                  AND rule_row.workspace_id = ?
                  AND rule_row.created_by_user_id = ?
                  AND rule_row.status = 'ACTIVE'
                  AND rule_row.owner_kind = 'PERSONAL'
                  AND rule_row.rule_kind = 'RELATIVE'
                """,
                currentNodeRevision,
                afterValue,
                mutationTimestamp,
                mutationTimestamp,
                node.id(),
                node.planId(),
                node.workspaceId(),
                node.ownerId());
    }

    private void requireAuthorization(
            Capability capability,
            NodeState node,
            WorkspaceContext context) {
        Long matches = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_share share_row
                LEFT JOIN calendar_share_scope_item item
                  ON item.snapshot_id =
                      share_row.current_scope_snapshot_id
                 AND item.share_id = share_row.id
                WHERE share_row.id = ? AND share_row.plan_id = ?
                  AND share_row.workspace_id = ?
                  AND share_row.created_by_user_id = ?
                  AND share_row.grantee_user_id = ?
                  AND share_row.permission = 'EDITOR'
                  AND share_row.status = 'ACTIVE'
                  AND (
                      share_row.scope_mode = 'LIVE_WHOLE_PLAN'
                      OR (item.target_kind = 'NODE'
                          AND item.node_id = ?))
                  AND (
                      ? = 'PLAN'
                      OR (? = 'NODE' AND ? = ?))
                """,
                Long.class,
                capability.shareId(),
                node.planId(),
                context.workspaceId(),
                capability.ownerId(),
                context.actorId(),
                node.id(),
                capability.scope(),
                capability.scope(),
                capability.nodeId(),
                node.id());
        if (matches == null || matches < 1) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_SCOPE_DENIED",
                    "Authoritative capability does not cover this node");
        }
    }

    private CalendarAuthoritativeMutationResult replay(
            String requestHash,
            UUID capabilityId,
            UUID nodeId,
            long expectedRevision,
            String reason,
            String source,
            CalendarAuthoritativeMutationResult.Kind kind,
            String afterValue,
            WorkspaceContext context) {
        List<AuditReplay> rows = jdbc.query(
                """
                SELECT mutation_id, capability_id, node_id,
                       mutation_kind, reason, source, after_value,
                       previous_target_revision, current_target_revision
                FROM calendar_authoritative_mutation_audit
                WHERE workspace_id = ? AND editor_user_id = ?
                  AND operation_request_hash = ?
                """,
                (resultSet, rowNumber) -> new AuditReplay(
                        resultSet.getObject("mutation_id", UUID.class),
                        resultSet.getObject("capability_id", UUID.class),
                        resultSet.getObject("node_id", UUID.class),
                        resultSet.getString("mutation_kind"),
                        resultSet.getString("reason"),
                        resultSet.getString("source"),
                        resultSet.getString("after_value"),
                        resultSet.getLong("previous_target_revision"),
                        resultSet.getLong("current_target_revision")),
                context.workspaceId(),
                context.actorId(),
                requestHash);
        if (rows.isEmpty()) {
            rows = jdbc.query(
                    """
                    SELECT mutation_id, capability_id, node_id,
                           mutation_kind, reason, source, after_value,
                           previous_target_revision,
                           current_target_revision
                    FROM calendar_authoritative_mutation_audit
                    WHERE workspace_id = ? AND editor_user_id = ?
                      AND capability_id = ? AND node_id = ?
                      AND mutation_kind = ? AND reason = ?
                      AND source = ? AND after_value = ?
                      AND previous_target_revision = ?
                    """,
                    (resultSet, rowNumber) -> new AuditReplay(
                            resultSet.getObject(
                                    "mutation_id", UUID.class),
                            resultSet.getObject(
                                    "capability_id", UUID.class),
                            resultSet.getObject(
                                    "node_id", UUID.class),
                            resultSet.getString("mutation_kind"),
                            resultSet.getString("reason"),
                            resultSet.getString("source"),
                            resultSet.getString("after_value"),
                            resultSet.getLong(
                                    "previous_target_revision"),
                            resultSet.getLong(
                                    "current_target_revision")),
                    context.workspaceId(),
                    context.actorId(),
                    capabilityId,
                    nodeId,
                    kind.name(),
                    reason,
                    source,
                    afterValue,
                    expectedRevision);
            if (rows.isEmpty()) {
                return null;
            }
        }
        AuditReplay row = rows.getFirst();
        if (!row.capabilityId().equals(capabilityId)
                || !row.nodeId().equals(nodeId)
                || !row.kind().equals(kind.name())
                || !row.reason().equals(reason)
                || !row.source().equals(source)
                || !row.afterValue().equals(afterValue)
                || row.previousRevision() != expectedRevision) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another authoritative mutation");
        }
        return new CalendarAuthoritativeMutationResult(
                row.mutationId(),
                row.nodeId(),
                kind,
                row.currentRevision());
    }

    private Capability findCapability(
            UUID capabilityId,
            WorkspaceContext context,
            boolean lock) {
        String lockClause = lock ? " FOR SHARE" : "";
        List<Capability> rows = jdbc.query(
                """
                SELECT id, share_id, plan_id, node_id,
                       capability_scope, created_by_user_id
                FROM calendar_authoritative_editor_capability
                WHERE id = ? AND workspace_id = ?
                  AND grantee_user_id = ? AND status = 'ACTIVE'
                """
                        + lockClause,
                (resultSet, rowNumber) -> new Capability(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("share_id", UUID.class),
                        resultSet.getObject("plan_id", UUID.class),
                        resultSet.getObject("node_id", UUID.class),
                        resultSet.getString("capability_scope"),
                        resultSet.getObject(
                                "created_by_user_id", UUID.class)),
                capabilityId,
                context.workspaceId(),
                context.actorId());
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar authoritative capability",
                    "active recipient capability");
        }
        return rows.getFirst();
    }

    private void lockPlan(
            UUID planId, UUID ownerId, WorkspaceContext context) {
        requireOne(jdbc.queryForList(
                """
                SELECT id FROM calendar_plan
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR SHARE
                """,
                UUID.class,
                planId,
                context.workspaceId(),
                ownerId));
    }

    private void lockShare(
            UUID shareId, UUID ownerId, WorkspaceContext context) {
        requireOne(jdbc.queryForList(
                """
                SELECT id FROM calendar_share
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND grantee_user_id = ?
                  AND permission = 'EDITOR' AND status = 'ACTIVE'
                FOR SHARE
                """,
                UUID.class,
                shareId,
                context.workspaceId(),
                ownerId,
                context.actorId()));
    }

    private NodeState lockNode(
            UUID nodeId,
            UUID planId,
            UUID ownerId,
            WorkspaceContext context) {
        List<NodeState> rows = jdbc.query(
                """
                SELECT id, plan_id, expression_kind,
                       cancellation_status, revision, updated_at,
                       workspace_id, created_by_user_id
                FROM calendar_time_node
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new NodeState(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("plan_id", UUID.class),
                        resultSet.getString("expression_kind"),
                        resultSet.getString("cancellation_status"),
                        resultSet.getLong("revision"),
                        resultSet.getTimestamp("updated_at").toInstant(),
                        resultSet.getObject(
                                "workspace_id", UUID.class),
                        resultSet.getObject(
                                "created_by_user_id", UUID.class)),
                nodeId,
                planId,
                context.workspaceId(),
                ownerId);
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar authoritative node",
                    "authorized node");
        }
        return rows.getFirst();
    }

    private static void requireOne(List<UUID> rows) {
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar authoritative resource",
                    "authorized resource");
        }
    }

    private void lockRequest(String requestHash) {
        jdbc.queryForObject(
                """
                SELECT pg_advisory_xact_lock(
                    hashtextextended(?, 0)) IS NULL
                """,
                Boolean.class,
                requestHash);
    }

    private static String payload(
            UUID mutationId,
            UUID capabilityId,
            UUID nodeId,
            CalendarAuthoritativeMutationResult.Kind kind,
            long revision,
            String reason,
            String source) {
        return "mutationId="
                + mutationId
                + ";capabilityId="
                + capabilityId
                + ";nodeId="
                + nodeId
                + ";kind="
                + kind
                + ";revision="
                + revision
                + ";reason="
                + reason
                + ";source="
                + source;
    }

    @FunctionalInterface
    private interface NodeUpdater {
        int update(NodeState node, Instant now);
    }

    private record Capability(
            UUID id,
            UUID shareId,
            UUID planId,
            UUID nodeId,
            String scope,
            UUID ownerId) {}

    private record NodeState(
            UUID id,
            UUID planId,
            String expressionKind,
            String cancellationStatus,
            long revision,
            Instant updatedAt,
            UUID workspaceId,
            UUID ownerId) {}

    private record AuditReplay(
            UUID mutationId,
            UUID capabilityId,
            UUID nodeId,
            String kind,
            String reason,
            String source,
            String afterValue,
            long previousRevision,
            long currentRevision) {}
}
