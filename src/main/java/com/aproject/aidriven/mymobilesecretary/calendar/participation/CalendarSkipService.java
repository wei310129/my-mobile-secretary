package com.aproject.aidriven.mymobilesecretary.calendar.participation;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarSkipService {

    private final CalendarParticipationAccess access;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarSkipService(
            CalendarParticipationAccess access,
            JdbcTemplate jdbc,
            Clock clock) {
        this.access = access;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarSkipImpactPreview preview(
            CalendarSkipPreviewRequest request) {
        if (request == null
                || request.planId() == null
                || request.scope() == null) {
            throw new IllegalArgumentException(
                    "Calendar skip plan and scope are required");
        }
        CalendarParticipationAccess.Target target =
                access.lockVisibleTarget(request.planId(), request.scope());
        access.lock("calendar-skip-preview|"
                + target.context().workspaceId()
                + "|"
                + target.context().actorId()
                + "|"
                + target.planId()
                + "|"
                + target.scopeType()
                + "|"
                + target.activityId());
        Impact impact = impact(target);
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        String tokenHash = CalendarParticipationAccess.hash(token);
        String digest = digest(target, impact);
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                INSERT INTO calendar_skip_confirmation (
                    id, adoption_id, participation_id, plan_id,
                    activity_id, share_id, target_scope,
                    expected_participation_revision,
                    expected_projection_revision,
                    expected_source_revision, preview_token_hash,
                    impact_preview_hash, previewed_at, expires_at,
                    confirmation_request_hash,
                    confirmation_payload_hash, consumed_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        NULL, NULL, NULL, ?, ?, ?)
                """,
                UUID.randomUUID(),
                impact.adoptionId(),
                impact.participationId(),
                target.planId(),
                target.activityId(),
                target.shareId(),
                target.scopeType().name(),
                impact.confirmedParticipationRevision(),
                impact.projectionRevision(),
                target.sourceRevision(),
                tokenHash,
                digest,
                Timestamp.from(now),
                Timestamp.from(now.plus(15, ChronoUnit.MINUTES)),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        return new CalendarSkipImpactPreview(
                token,
                digest,
                target.sourceRevision(),
                impact.strongConfirmation(),
                impact.nodeIds(),
                impact.reminderIds(),
                impact.retainedItemIds());
    }

    public CalendarSkipResult confirm(
            CalendarSkipConfirmation command) {
        if (command == null
                || command.requestId() == null
                || command.confirmationToken() == null
                || command.digest() == null
                || command.sourceRevision() < 0) {
            throw new IllegalArgumentException(
                    "Calendar skip confirmation identity is required");
        }
        WorkspaceContext context =
                CalendarParticipationAccess.tenantContext();
        String requestHash =
                CalendarParticipationAccess.hash(command.requestId());
        String tokenHash =
                CalendarParticipationAccess.hash(command.confirmationToken());
        String payloadHash = CalendarParticipationAccess.hash(
                tokenHash
                        + "|"
                        + command.digest()
                        + "|"
                        + command.sourceRevision());
        access.lock("calendar-skip-confirm-request|" + requestHash);
        access.lock("calendar-skip-confirm-token|" + tokenHash);
        Preview preview = lockPreview(tokenHash, context);
        if (preview == null) {
            throw new BusinessException(
                    "CALENDAR_SKIP_PREVIEW_NOT_FOUND",
                    "The skip preview is invalid or belongs to another actor");
        }
        if (preview.consumedAt() != null) {
            requireReplay(preview, requestHash, payloadHash);
            return result(preview.id(), context, true);
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(preview.expiresAt())
                || !preview.digest().equals(command.digest())
                || preview.sourceRevision() != command.sourceRevision()) {
            throw stalePreview();
        }
        CalendarParticipationScope scope =
                new CalendarParticipationScope(
                        CalendarParticipationScopeType.valueOf(
                                preview.targetScope()),
                        preview.activityId() == null
                                ? preview.planId()
                                : preview.activityId());
        CalendarParticipationAccess.Target target =
                access.lockVisibleTarget(preview.planId(), scope);
        Impact currentImpact = impact(target);
        if (!preview.digest().equals(digest(target, currentImpact))
                || !java.util.Objects.equals(
                        preview.participationRevision(),
                        currentImpact.confirmedParticipationRevision())
                || !java.util.Objects.equals(
                        preview.projectionRevision(),
                        currentImpact.projectionRevision())) {
            throw stalePreview();
        }
        CalendarParticipationStatus finalStatus =
                applyParticipationOptOut(
                        preview, currentImpact, requestHash, payloadHash, now, context);
        UUID suppressionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_projection_suppression (
                    id, confirmation_id, adoption_id, participation_id,
                    plan_id, activity_id, target_scope,
                    participation_revision, projection_revision,
                    source_revision, suppression_revision, status,
                    constraints_suppressed, future_reminders_suppressed,
                    routine_messages_suppressed, created_at, updated_at,
                    released_at, workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 'ACTIVE',
                        TRUE, TRUE, TRUE, ?, ?, NULL, ?, ?, ?)
                """,
                suppressionId,
                preview.id(),
                currentImpact.adoptionId(),
                currentImpact.participationId(),
                preview.planId(),
                preview.activityId(),
                preview.targetScope(),
                currentImpact.confirmedParticipationRevision(),
                currentImpact.projectionRevision(),
                target.sourceRevision(),
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                target.sourceOwnerId());
        stopRoutine(preview, target, requestHash, payloadHash, now);
        cancelReminderOccurrences(currentImpact.reminderIds(), context, now);
        int consumed = jdbc.update(
                """
                UPDATE calendar_skip_confirmation
                SET confirmation_request_hash = ?,
                    confirmation_payload_hash = ?, consumed_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND consumed_at IS NULL
                """,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                preview.id(),
                context.workspaceId(),
                context.actorId());
        if (consumed != 1) {
            throw stalePreview();
        }
        insertOutbox(
                suppressionId,
                preview,
                target,
                requestHash,
                payloadHash,
                now);
        return new CalendarSkipResult(
                finalStatus,
                currentImpact.adoptionId() != null,
                true,
                currentImpact.reminderIds(),
                false);
    }

    private CalendarParticipationStatus applyParticipationOptOut(
            Preview preview,
            Impact impact,
            String requestHash,
            String payloadHash,
            Instant now,
            WorkspaceContext context) {
        if (impact.participationId() == null) {
            return CalendarParticipationStatus.OPTED_OUT;
        }
        String previous = jdbc.queryForObject(
                """
                SELECT participation_state
                FROM calendar_participation
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                String.class,
                impact.participationId(),
                context.workspaceId(),
                context.actorId());
        if ("OPTED_OUT".equals(previous)) {
            return CalendarParticipationStatus.OPTED_OUT;
        }
        long revision = impact.participationRevision() + 1;
        jdbc.update(
                """
                UPDATE calendar_participation
                SET participation_state = 'OPTED_OUT',
                    participation_revision = ?,
                    source_revision = ?,
                    operation_request_hash = ?,
                    operation_payload_hash = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND participation_revision = ?
                """,
                revision,
                preview.sourceRevision(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                impact.participationId(),
                context.workspaceId(),
                context.actorId(),
                impact.participationRevision());
        jdbc.update(
                """
                INSERT INTO calendar_participation_history (
                    id, participation_id, plan_id, activity_id,
                    target_scope, event_type, previous_state,
                    current_state, participation_revision,
                    source_revision, operation_request_hash,
                    operation_payload_hash, occurred_at, workspace_id,
                    created_by_user_id, source_created_by_user_id)
                SELECT ?, id, plan_id, activity_id, target_scope,
                       'STATE_CHANGED', ?, 'OPTED_OUT', ?, ?, ?, ?, ?,
                       workspace_id, created_by_user_id,
                       source_created_by_user_id
                FROM calendar_participation
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                UUID.randomUUID(),
                previous,
                revision,
                preview.sourceRevision(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                impact.participationId(),
                context.workspaceId(),
                context.actorId());
        return CalendarParticipationStatus.OPTED_OUT;
    }

    private void stopRoutine(
            Preview preview,
            CalendarParticipationAccess.Target target,
            String requestHash,
            String payloadHash,
            Instant now) {
        jdbc.update(
                """
                UPDATE calendar_routine_subscription
                SET subscription_state = 'UNSUBSCRIBED',
                    subscription_revision = subscription_revision + 1,
                    source_revision = ?,
                    operation_request_hash = ?,
                    operation_payload_hash = ?,
                    updated_at = ?, unsubscribed_at = ?
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ?
                  AND subscription_state = 'WATCHING'
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                target.sourceRevision(),
                CalendarParticipationAccess.hash(
                        "skip-watch|" + requestHash),
                CalendarParticipationAccess.hash(
                        "skip-watch|" + payloadHash),
                Timestamp.from(now),
                Timestamp.from(now),
                preview.planId(),
                preview.activityId(),
                preview.targetScope(),
                target.context().workspaceId(),
                target.context().actorId());
    }

    private void cancelReminderOccurrences(
            List<UUID> reminderIds,
            WorkspaceContext context,
            Instant now) {
        for (UUID ruleId : reminderIds) {
            jdbc.update(
                    """
                    UPDATE calendar_reminder_occurrence
                    SET status = 'CANCELED', version = version + 1,
                        updated_at = ?
                    WHERE rule_id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                      AND status IN ('PENDING', 'ENQUEUED')
                    """,
                    Timestamp.from(now),
                    ruleId,
                    context.workspaceId(),
                    context.actorId());
        }
    }

    private void insertOutbox(
            UUID suppressionId,
            Preview preview,
            CalendarParticipationAccess.Target target,
            String requestHash,
            String payloadHash,
            Instant now) {
        String semanticHash = CalendarParticipationAccess.hash(
                "SKIP_CONFIRMED|" + suppressionId);
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
                VALUES (?, 'SKIP_CONFIRMED', NULL, NULL, NULL, NULL,
                        NULL, ?, ?, ?, ?, ?, ?,
                        'Calendar scope skipped by its participant',
                        'PENDING', 0, ?, NULL, NULL, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                suppressionId,
                preview.planId(),
                target.sourceRevision(),
                requestHash,
                payloadHash,
                semanticHash,
                Timestamp.from(now),
                Timestamp.from(now),
                Timestamp.from(now),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
    }

    private Impact impact(CalendarParticipationAccess.Target target) {
        Participation participation = participation(target);
        Adoption adoption = adoption(target);
        List<NodeImpact> nodes = adoption == null
                ? List.of()
                : jdbc.query(
                        """
                        SELECT projection.source_node_id,
                               source.revision, source.criticality
                        FROM calendar_personal_projection_snapshot snapshot
                        JOIN calendar_personal_projection_node projection
                          ON projection.snapshot_id = snapshot.id
                         AND projection.workspace_id =
                             snapshot.workspace_id
                         AND projection.created_by_user_id =
                             snapshot.created_by_user_id
                        JOIN calendar_time_node source
                          ON source.id = projection.source_node_id
                         AND source.plan_id = projection.plan_id
                         AND source.workspace_id =
                             projection.workspace_id
                         AND source.created_by_user_id =
                             projection.source_created_by_user_id
                        WHERE snapshot.adoption_id = ?
                          AND snapshot.workspace_id = ?
                          AND snapshot.created_by_user_id = ?
                          AND snapshot.projection_status <>
                              'SUPERSEDED'
                          AND (CAST(? AS uuid) IS NULL
                               OR projection.activity_id = ?)
                        ORDER BY projection.source_node_id
                        """,
                        (row, ignored) -> new NodeImpact(
                                row.getObject("source_node_id", UUID.class),
                                row.getLong("revision"),
                                row.getString("criticality")),
                        adoption.id(),
                        target.context().workspaceId(),
                        target.context().actorId(),
                        target.activityId(),
                        target.activityId());
        List<UUID> nodeIds =
                nodes.stream().map(NodeImpact::id).toList();
        List<UUID> reminderIds = nodeIds.isEmpty()
                ? List.of()
                : jdbc.query(
                        """
                        SELECT rule.id
                        FROM calendar_reminder_rule rule
                        WHERE rule.plan_id = ? AND rule.status = 'ACTIVE'
                          AND rule.workspace_id = ?
                          AND rule.created_by_user_id = ?
                          AND rule.node_id = ANY (
                              string_to_array(?, ',')::uuid[])
                        ORDER BY rule.id
                        """,
                        (row, ignored) -> row.getObject("id", UUID.class),
                        target.planId(),
                        target.context().workspaceId(),
                        target.context().actorId(),
                        joinedIds(nodeIds));
        List<UUID> retained = retainedItems(target, nodeIds);
        boolean strong = participation != null
                        && ("COMMITTED".equals(participation.state())
                                || "REQUIRED".equals(participation.policy()))
                || adoption != null
                || !reminderIds.isEmpty()
                || nodes.stream().anyMatch(
                        node -> "CRITICAL".equals(node.criticality()))
                || !retained.isEmpty();
        return new Impact(
                participation == null ? null : participation.id(),
                participation == null ? null : participation.revision(),
                participation == null ? null : participation.state(),
                adoption == null ? null : adoption.id(),
                adoption == null ? null : adoption.projectionRevision(),
                nodes,
                reminderIds,
                retained,
                strong);
    }

    private List<UUID> retainedItems(
            CalendarParticipationAccess.Target target,
            List<UUID> nodeIds) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT binding.id
                FROM calendar_task_binding binding
                WHERE binding.workspace_id = ?
                  AND binding.created_by_user_id = ?
                  AND binding.node_id = ANY (
                      string_to_array(?, ',')::uuid[])
                UNION ALL
                SELECT binding.id
                FROM calendar_knowledge_fact_binding binding
                WHERE binding.workspace_id = ?
                  AND binding.created_by_user_id = ?
                  AND binding.node_id = ANY (
                      string_to_array(?, ',')::uuid[])
                UNION ALL
                SELECT binding.id
                FROM calendar_knowledge_annotation_binding binding
                WHERE binding.workspace_id = ?
                  AND binding.created_by_user_id = ?
                  AND binding.node_id = ANY (
                      string_to_array(?, ',')::uuid[])
                ORDER BY 1
                """,
                (row, ignored) -> row.getObject("id", UUID.class),
                target.context().workspaceId(),
                target.context().actorId(),
                joinedIds(nodeIds),
                target.context().workspaceId(),
                target.context().actorId(),
                joinedIds(nodeIds),
                target.context().workspaceId(),
                target.context().actorId(),
                joinedIds(nodeIds));
    }

    private static String joinedIds(List<UUID> ids) {
        return ids.stream()
                .map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Participation participation(
            CalendarParticipationAccess.Target target) {
        List<Participation> rows = jdbc.query(
                """
                SELECT id, participation_state, participation_policy,
                       participation_revision
                FROM calendar_participation
                WHERE plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND target_scope = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND source_created_by_user_id = ?
                FOR UPDATE
                """,
                (row, ignored) -> new Participation(
                        row.getObject("id", UUID.class),
                        row.getString("participation_state"),
                        row.getString("participation_policy"),
                        row.getLong("participation_revision")),
                target.planId(),
                target.activityId(),
                target.scopeType().name(),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        return rows.stream().findFirst().orElse(null);
    }

    private Adoption adoption(
            CalendarParticipationAccess.Target target) {
        List<Adoption> rows = jdbc.query(
                """
                SELECT adoption.id, snapshot.projection_revision
                FROM calendar_adoption adoption
                JOIN calendar_personal_projection_snapshot snapshot
                  ON snapshot.adoption_id = adoption.id
                 AND snapshot.workspace_id = adoption.workspace_id
                 AND snapshot.created_by_user_id =
                     adoption.created_by_user_id
                 AND snapshot.projection_status <> 'SUPERSEDED'
                WHERE adoption.plan_id = ? AND adoption.status = 'ACTIVE'
                  AND adoption.workspace_id = ?
                  AND adoption.created_by_user_id = ?
                  AND adoption.source_created_by_user_id = ?
                FOR UPDATE OF adoption, snapshot
                """,
                (row, ignored) -> new Adoption(
                        row.getObject("id", UUID.class),
                        row.getLong("projection_revision")),
                target.planId(),
                target.context().workspaceId(),
                target.context().actorId(),
                target.sourceOwnerId());
        return rows.stream().findFirst().orElse(null);
    }

    private String digest(
            CalendarParticipationAccess.Target target, Impact impact) {
        StringBuilder value = new StringBuilder()
                .append(target.planId())
                .append('|')
                .append(target.scopeType())
                .append('|')
                .append(target.activityId())
                .append('|')
                .append(target.sourceRevision())
                .append('|')
                .append(impact.participationRevision())
                .append('|')
                .append(impact.projectionRevision());
        impact.nodes().forEach(node -> value.append('|')
                .append(node.id())
                .append(':')
                .append(node.revision()));
        impact.reminderIds().forEach(id -> value.append("|r:").append(id));
        impact.retainedItemIds().forEach(id -> value.append("|k:").append(id));
        return CalendarParticipationAccess.hash(value.toString());
    }

    private Preview lockPreview(
            String tokenHash, WorkspaceContext context) {
        List<Preview> rows = jdbc.query(
                """
                SELECT id, adoption_id, participation_id, plan_id,
                       activity_id, target_scope,
                       expected_participation_revision,
                       expected_projection_revision,
                       expected_source_revision, impact_preview_hash,
                       expires_at, confirmation_request_hash,
                       confirmation_payload_hash, consumed_at
                FROM calendar_skip_confirmation
                WHERE preview_token_hash = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                CalendarSkipService::preview,
                tokenHash,
                context.workspaceId(),
                context.actorId());
        return rows.stream().findFirst().orElse(null);
    }

    private CalendarSkipResult result(
            UUID confirmationId,
            WorkspaceContext context,
            boolean replayed) {
        Suppression suppression = jdbc.queryForObject(
                """
                SELECT adoption_id, participation_id
                FROM calendar_projection_suppression
                WHERE confirmation_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                (row, ignored) -> new Suppression(
                        row.getObject("adoption_id", UUID.class),
                        row.getObject("participation_id", UUID.class)),
                confirmationId,
                context.workspaceId(),
                context.actorId());
        return new CalendarSkipResult(
                CalendarParticipationStatus.OPTED_OUT,
                suppression != null && suppression.adoptionId() != null,
                true,
                List.of(),
                replayed);
    }

    private static Preview preview(ResultSet row, int ignored)
            throws SQLException {
        Timestamp consumed = row.getTimestamp("consumed_at");
        return new Preview(
                row.getObject("id", UUID.class),
                row.getObject("adoption_id", UUID.class),
                row.getObject("participation_id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getObject("activity_id", UUID.class),
                row.getString("target_scope"),
                nullableLong(row, "expected_participation_revision"),
                nullableLong(row, "expected_projection_revision"),
                row.getLong("expected_source_revision"),
                row.getString("impact_preview_hash"),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("confirmation_request_hash"),
                row.getString("confirmation_payload_hash"),
                consumed == null ? null : consumed.toInstant());
    }

    private static Long nullableLong(ResultSet row, String column)
            throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void requireReplay(
            Preview preview, String requestHash, String payloadHash) {
        if (!requestHash.equals(preview.confirmationRequestHash())
                || !payloadHash.equals(preview.confirmationPayloadHash())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The skip confirmation was already consumed by another request");
        }
    }

    private static BusinessException stalePreview() {
        return new BusinessException(
                "CALENDAR_SKIP_PREVIEW_STALE",
                "Calendar skip impact changed; preview again before confirming");
    }

    private record Participation(
            UUID id, String state, String policy, long revision) {}

    private record Adoption(UUID id, long projectionRevision) {}

    private record NodeImpact(
            UUID id, long revision, String criticality) {}

    private record Impact(
            UUID participationId,
            Long participationRevision,
            String participationState,
            UUID adoptionId,
            Long projectionRevision,
            List<NodeImpact> nodes,
            List<UUID> reminderIds,
            List<UUID> retainedItemIds,
            boolean strongConfirmation) {

        List<UUID> nodeIds() {
            return nodes.stream().map(NodeImpact::id).toList();
        }

        Long confirmedParticipationRevision() {
            if (participationRevision == null) {
                return null;
            }
            return "OPTED_OUT".equals(participationState)
                    ? participationRevision
                    : participationRevision + 1;
        }
    }

    private record Preview(
            UUID id,
            UUID adoptionId,
            UUID participationId,
            UUID planId,
            UUID activityId,
            String targetScope,
            Long participationRevision,
            Long projectionRevision,
            long sourceRevision,
            String digest,
            Instant expiresAt,
            String confirmationRequestHash,
            String confirmationPayloadHash,
            Instant consumedAt) {}

    private record Suppression(UUID adoptionId, UUID participationId) {}
}
