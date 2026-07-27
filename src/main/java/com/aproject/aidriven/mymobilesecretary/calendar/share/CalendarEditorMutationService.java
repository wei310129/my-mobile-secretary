package com.aproject.aidriven.mymobilesecretary.calendar.share;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
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
public class CalendarEditorMutationService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarEditorMutationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarEditorMutationResult changeNodeLabel(
            CalendarEditorNodeLabelChange change) {
        WorkspaceContext context = context();
        String requestHash =
                CalendarShareService.hash(change.requestKey());
        lockRequest(requestHash);
        CalendarEditorMutationResult replay =
                replay(change, requestHash, context);
        if (replay != null) {
            return replay;
        }

        ShareIdentity identity =
                findShareIdentity(change.shareId(), context);
        lockPlan(identity.planId(), identity.ownerId(), context);
        ShareIdentity share =
                lockEditorShare(change.shareId(), context);
        NodeState node = lockNode(
                change.nodeId(),
                share.planId(),
                share.ownerId(),
                context);
        if (node.revision() != change.expectedNodeRevision()) {
            throw new BusinessException(
                    "CALENDAR_EDITOR_NODE_REVISION_CONFLICT",
                    "Calendar node changed; reload before editing");
        }
        if (node.label().equals(change.label())) {
            throw new BusinessException(
                    "CALENDAR_EDITOR_MUTATION_NO_CHANGE",
                    "Calendar editor mutation must change the node label");
        }

        UUID mutationId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        long nextRevision = node.revision() + 1;
        jdbc.update(
                """
                INSERT INTO calendar_editor_mutation_audit (
                    id, mutation_id, share_id, plan_id, node_id,
                    activity_id, mutation_kind, before_value, after_value,
                    previous_target_revision, current_target_revision,
                    operation_request_hash, occurred_at, workspace_id,
                    created_by_user_id, editor_user_id)
                VALUES (?, ?, ?, ?, ?, NULL, 'NODE_LABEL', ?, ?, ?, ?,
                        ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                mutationId,
                share.id(),
                share.planId(),
                change.nodeId(),
                node.label(),
                change.label(),
                node.revision(),
                nextRevision,
                requestHash,
                Timestamp.from(now),
                context.workspaceId(),
                share.ownerId(),
                context.actorId());
        jdbc.queryForObject(
                """
                SELECT set_config(
                    'app.calendar_editor_mutation_id', ?, true)
                """,
                String.class,
                mutationId.toString());
        int updated = jdbc.update(
                """
                UPDATE calendar_time_node
                SET label = ?, revision = ?, version = version + 1,
                    updated_at = ?
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND revision = ?
                """,
                change.label(),
                nextRevision,
                Timestamp.from(now),
                change.nodeId(),
                share.planId(),
                context.workspaceId(),
                share.ownerId(),
                node.revision());
        if (updated != 1) {
            throw new BusinessException(
                    "CALENDAR_EDITOR_NODE_REVISION_CONFLICT",
                    "Calendar node changed; reload before editing");
        }
        jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id, created_by_user_id,
                    editor_mutation_id)
                VALUES (?, ?, 'EDITOR_MUTATION', ?, NULL, ?, ?,
                        'PENDING', ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                CalendarShareService.hash(
                        "EDITOR_MUTATION|" + mutationId),
                share.id(),
                context.actorId(),
                "mutationId="
                        + mutationId
                        + ";Calendar editor changed a node label",
                Timestamp.from(now),
                context.workspaceId(),
                share.ownerId(),
                mutationId);
        return new CalendarEditorMutationResult(
                mutationId,
                change.nodeId(),
                change.label(),
                nextRevision);
    }

    public CalendarEditorActivityMutationResult changeActivity(
            CalendarEditorActivityChange change) {
        WorkspaceContext context = context();
        String requestHash =
                CalendarShareService.hash(change.requestKey());
        lockRequest(requestHash);
        CalendarEditorActivityMutationResult replay =
                replayActivity(change, requestHash, context);
        if (replay != null) {
            return replay;
        }

        ShareIdentity identity =
                findShareIdentity(change.shareId(), context);
        lockPlan(identity.planId(), identity.ownerId(), context);
        ShareIdentity share =
                lockEditorShare(change.shareId(), context);
        ActivityState activity = lockActivity(
                change.activityId(),
                share.planId(),
                share.ownerId(),
                context);
        if (activity.version() != change.expectedVersion()) {
            throw new BusinessException(
                    "CALENDAR_EDITOR_ACTIVITY_VERSION_CONFLICT",
                    "Calendar activity changed; reload before editing");
        }
        String beforeValue =
                change.field() == CalendarEditorActivityChange.Field.TITLE
                        ? activity.title()
                        : nullToEmpty(activity.category());
        if (beforeValue.equals(change.value())) {
            throw new BusinessException(
                    "CALENDAR_EDITOR_MUTATION_NO_CHANGE",
                    "Calendar editor mutation must change the activity");
        }

        UUID mutationId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        long nextVersion = activity.version() + 1;
        String mutationKind =
                "ACTIVITY_" + change.field().name();
        jdbc.update(
                """
                INSERT INTO calendar_editor_mutation_audit (
                    id, mutation_id, share_id, plan_id, node_id,
                    activity_id, mutation_kind, before_value, after_value,
                    previous_target_revision, current_target_revision,
                    operation_request_hash, occurred_at, workspace_id,
                    created_by_user_id, editor_user_id)
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                mutationId,
                share.id(),
                share.planId(),
                change.activityId(),
                mutationKind,
                beforeValue,
                change.value(),
                activity.version(),
                nextVersion,
                requestHash,
                Timestamp.from(now),
                context.workspaceId(),
                share.ownerId(),
                context.actorId());
        jdbc.queryForObject(
                """
                SELECT set_config(
                    'app.calendar_editor_mutation_id', ?, true)
                """,
                String.class,
                mutationId.toString());
        int updated = change.field()
                        == CalendarEditorActivityChange.Field.TITLE
                ? updateActivityTitle(
                        change,
                        share,
                        nextVersion,
                        now,
                        context)
                : updateActivityCategory(
                        change,
                        share,
                        nextVersion,
                        now,
                        context);
        if (updated != 1) {
            throw new BusinessException(
                    "CALENDAR_EDITOR_ACTIVITY_VERSION_CONFLICT",
                    "Calendar activity changed; reload before editing");
        }
        insertMutationOutbox(
                mutationId, share, context, now,
                "Calendar editor changed an activity");
        return new CalendarEditorActivityMutationResult(
                mutationId,
                change.activityId(),
                change.field(),
                change.value(),
                nextVersion);
    }

    private CalendarEditorMutationResult replay(
            CalendarEditorNodeLabelChange change,
            String requestHash,
            WorkspaceContext context) {
        List<AuditReplay> rows = jdbc.query(
                """
                SELECT mutation_id, share_id, node_id, after_value,
                       previous_target_revision, current_target_revision
                FROM calendar_editor_mutation_audit
                WHERE workspace_id = ? AND editor_user_id = ?
                  AND operation_request_hash = ?
                """,
                (resultSet, rowNumber) -> new AuditReplay(
                        resultSet.getObject("mutation_id", UUID.class),
                        resultSet.getObject("share_id", UUID.class),
                        resultSet.getObject("node_id", UUID.class),
                        resultSet.getString("after_value"),
                        resultSet.getLong("previous_target_revision"),
                        resultSet.getLong("current_target_revision")),
                context.workspaceId(),
                context.actorId(),
                requestHash);
        if (rows.isEmpty()) {
            return null;
        }
        AuditReplay row = rows.getFirst();
        if (!row.shareId().equals(change.shareId())
                || !row.nodeId().equals(change.nodeId())
                || !row.afterValue().equals(change.label())
                || row.previousRevision()
                        != change.expectedNodeRevision()) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another editor mutation");
        }
        return new CalendarEditorMutationResult(
                row.mutationId(),
                row.nodeId(),
                row.afterValue(),
                row.currentRevision());
    }

    private CalendarEditorActivityMutationResult replayActivity(
            CalendarEditorActivityChange change,
            String requestHash,
            WorkspaceContext context) {
        List<ActivityAuditReplay> rows = jdbc.query(
                """
                SELECT mutation_id, share_id, activity_id, mutation_kind,
                       after_value, previous_target_revision,
                       current_target_revision
                FROM calendar_editor_mutation_audit
                WHERE workspace_id = ? AND editor_user_id = ?
                  AND operation_request_hash = ?
                """,
                (resultSet, rowNumber) -> new ActivityAuditReplay(
                        resultSet.getObject("mutation_id", UUID.class),
                        resultSet.getObject("share_id", UUID.class),
                        resultSet.getObject("activity_id", UUID.class),
                        resultSet.getString("mutation_kind"),
                        resultSet.getString("after_value"),
                        resultSet.getLong("previous_target_revision"),
                        resultSet.getLong("current_target_revision")),
                context.workspaceId(),
                context.actorId(),
                requestHash);
        if (rows.isEmpty()) {
            return null;
        }
        ActivityAuditReplay row = rows.getFirst();
        String expectedKind =
                "ACTIVITY_" + change.field().name();
        if (!row.shareId().equals(change.shareId())
                || !row.activityId().equals(change.activityId())
                || !row.mutationKind().equals(expectedKind)
                || !row.afterValue().equals(change.value())
                || row.previousVersion() != change.expectedVersion()) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another editor mutation");
        }
        return new CalendarEditorActivityMutationResult(
                row.mutationId(),
                row.activityId(),
                change.field(),
                row.afterValue(),
                row.currentVersion());
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

    private int updateActivityTitle(
            CalendarEditorActivityChange change,
            ShareIdentity share,
            long nextVersion,
            Instant now,
            WorkspaceContext context) {
        return jdbc.update(
                """
                UPDATE calendar_activity
                SET title = ?, version = ?, updated_at = ?
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND version = ?
                """,
                change.value(),
                nextVersion,
                Timestamp.from(now),
                change.activityId(),
                share.planId(),
                context.workspaceId(),
                share.ownerId(),
                change.expectedVersion());
    }

    private int updateActivityCategory(
            CalendarEditorActivityChange change,
            ShareIdentity share,
            long nextVersion,
            Instant now,
            WorkspaceContext context) {
        return jdbc.update(
                """
                UPDATE calendar_activity
                SET category = ?, version = ?, updated_at = ?
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND version = ?
                """,
                change.value(),
                nextVersion,
                Timestamp.from(now),
                change.activityId(),
                share.planId(),
                context.workspaceId(),
                share.ownerId(),
                change.expectedVersion());
    }

    private void insertMutationOutbox(
            UUID mutationId,
            ShareIdentity share,
            WorkspaceContext context,
            Instant now,
            String payload) {
        jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id, created_by_user_id,
                    editor_mutation_id)
                VALUES (?, ?, 'EDITOR_MUTATION', ?, NULL, ?, ?,
                        'PENDING', ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                CalendarShareService.hash(
                        "EDITOR_MUTATION|" + mutationId),
                share.id(),
                context.actorId(),
                "mutationId=" + mutationId + ";" + payload,
                Timestamp.from(now),
                context.workspaceId(),
                share.ownerId(),
                mutationId);
    }

    private ShareIdentity findShareIdentity(
            UUID shareId, WorkspaceContext context) {
        List<ShareIdentity> rows = jdbc.query(
                """
                SELECT id, plan_id, created_by_user_id
                FROM calendar_share
                WHERE id = ? AND workspace_id = ?
                  AND grantee_user_id = ? AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new ShareIdentity(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("plan_id", UUID.class),
                        resultSet.getObject(
                                "created_by_user_id", UUID.class)),
                shareId,
                context.workspaceId(),
                context.actorId());
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar editor share", "requested share");
        }
        return rows.getFirst();
    }

    private void lockPlan(
            UUID planId, UUID ownerId, WorkspaceContext context) {
        List<UUID> rows = jdbc.queryForList(
                """
                SELECT id FROM calendar_plan
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR SHARE
                """,
                UUID.class,
                planId,
                context.workspaceId(),
                ownerId);
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar plan", "shared plan");
        }
    }

    private ShareIdentity lockEditorShare(
            UUID shareId, WorkspaceContext context) {
        List<ShareIdentity> rows = jdbc.query(
                """
                SELECT id, plan_id, created_by_user_id
                FROM calendar_share
                WHERE id = ? AND workspace_id = ?
                  AND grantee_user_id = ? AND status = 'ACTIVE'
                  AND permission = 'EDITOR'
                FOR SHARE
                """,
                (resultSet, rowNumber) -> new ShareIdentity(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("plan_id", UUID.class),
                        resultSet.getObject(
                                "created_by_user_id", UUID.class)),
                shareId,
                context.workspaceId(),
                context.actorId());
        if (rows.isEmpty()) {
            throw new BusinessException(
                    "CALENDAR_EDITOR_PERMISSION_REQUIRED",
                    "An active calendar editor share is required");
        }
        return rows.getFirst();
    }

    private NodeState lockNode(
            UUID nodeId,
            UUID planId,
            UUID ownerId,
            WorkspaceContext context) {
        List<NodeState> rows = jdbc.query(
                """
                SELECT label, revision
                FROM calendar_time_node
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new NodeState(
                        resultSet.getString("label"),
                        resultSet.getLong("revision")),
                nodeId,
                planId,
                context.workspaceId(),
                ownerId);
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar editor node", "authorized node");
        }
        return rows.getFirst();
    }

    private ActivityState lockActivity(
            UUID activityId,
            UUID planId,
            UUID ownerId,
            WorkspaceContext context) {
        List<ActivityState> rows = jdbc.query(
                """
                SELECT title, category, version
                FROM calendar_activity
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new ActivityState(
                        resultSet.getString("title"),
                        resultSet.getString("category"),
                        resultSet.getLong("version")),
                activityId,
                planId,
                context.workspaceId(),
                ownerId);
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar editor activity",
                    "authorized activity");
        }
        return rows.getFirst();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private WorkspaceContext context() {
        return CalendarShareService.context();
    }

    private record ShareIdentity(
            UUID id, UUID planId, UUID ownerId) {}

    private record NodeState(String label, long revision) {}

    private record ActivityState(
            String title, String category, long version) {}

    private record AuditReplay(
            UUID mutationId,
            UUID shareId,
            UUID nodeId,
            String afterValue,
            long previousRevision,
            long currentRevision) {}

    private record ActivityAuditReplay(
            UUID mutationId,
            UUID shareId,
            UUID activityId,
            String mutationKind,
            String afterValue,
            long previousVersion,
            long currentVersion) {}
}
