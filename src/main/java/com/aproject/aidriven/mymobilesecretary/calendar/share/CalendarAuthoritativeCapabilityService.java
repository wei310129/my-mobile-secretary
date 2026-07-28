package com.aproject.aidriven.mymobilesecretary.calendar.share;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarAuthoritativeCapabilityService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarAuthoritativeCapabilityService(
            JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarAuthoritativeCapabilityView grant(
            CalendarAuthoritativeCapabilityGrant grant) {
        WorkspaceContext context = CalendarShareService.context();
        String requestHash =
                CalendarShareService.hash(grant.requestKey());
        lockRequest(requestHash);
        CalendarAuthoritativeCapabilityView replay =
                findByGrantRequest(requestHash, context);
        if (replay != null) {
            requireGrantReplay(replay, grant);
            return replay;
        }

        ShareState identity =
                findOwnedShare(grant.shareId(), context, false);
        lockPlan(identity.planId(), context);
        ShareState share =
                findOwnedShare(grant.shareId(), context, true);
        if (!"ACTIVE".equals(share.status())
                || !"EDITOR".equals(share.permission())) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_EDITOR_REQUIRED",
                    "An active calendar editor share is required");
        }
        if (share.revision() != grant.expectedShareRevision()) {
            throw new BusinessException(
                    "CALENDAR_SHARE_REVISION_CONFLICT",
                    "Calendar share changed; reload before granting capability");
        }
        requireScopeIntersection(grant, share, context);

        UUID capabilityId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                INSERT INTO calendar_authoritative_editor_capability (
                    id, share_id, plan_id, node_id, capability_scope,
                    status, capability_revision, grant_request_hash,
                    revoke_request_hash, granted_at, revoked_at,
                    workspace_id, created_by_user_id, grantee_user_id)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', 1, ?, NULL, ?, NULL,
                        ?, ?, ?)
                """,
                capabilityId,
                share.id(),
                share.planId(),
                grant.nodeId(),
                grant.scope().name(),
                requestHash,
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId(),
                share.granteeId());
        insertOutbox(
                "AUTHORITATIVE_CAPABILITY_GRANTED",
                requestHash,
                share,
                "capabilityId="
                        + capabilityId
                        + ";capabilityRevision=1;scope="
                        + grant.scope()
                        + ";nodeId="
                        + Objects.toString(grant.nodeId(), ""),
                now,
                context);
        return getOwned(capabilityId, context);
    }

    public CalendarAuthoritativeCapabilityView revoke(
            String requestKey,
            UUID capabilityId,
            long expectedRevision) {
        WorkspaceContext context = CalendarShareService.context();
        String requestHash = CalendarShareService.hash(
                CalendarShareService.requireKey(requestKey));
        lockRequest(requestHash);
        CapabilityState identity =
                findCapability(capabilityId, context, false);
        if ("REVOKED".equals(identity.status())) {
            if (requestHash.equals(identity.revokeRequestHash())) {
                return view(identity);
            }
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_CAPABILITY_REVOKED",
                    "Authoritative capability is already revoked");
        }
        lockPlan(identity.planId(), context);
        findOwnedShare(identity.shareId(), context, true);
        CapabilityState capability =
                findCapability(capabilityId, context, true);
        if (capability.revision() != expectedRevision) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_CAPABILITY_REVISION_CONFLICT",
                    "Authoritative capability changed; reload before revoking");
        }
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                """
                UPDATE calendar_authoritative_editor_capability
                SET status = 'REVOKED',
                    capability_revision = capability_revision + 1,
                    revoke_request_hash = ?, revoked_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                  AND capability_revision = ?
                """,
                requestHash,
                Timestamp.from(now),
                capabilityId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (updated != 1) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_CAPABILITY_REVISION_CONFLICT",
                    "Authoritative capability changed; reload before revoking");
        }
        ShareState share =
                findOwnedShare(capability.shareId(), context, false);
        insertOutbox(
                "AUTHORITATIVE_CAPABILITY_REVOKED",
                requestHash,
                share,
                "capabilityId="
                        + capabilityId
                        + ";capabilityRevision="
                        + (expectedRevision + 1)
                        + ";cause=EXPLICIT_REVOKE",
                now,
                context);
        return getOwned(capabilityId, context);
    }

    private void requireScopeIntersection(
            CalendarAuthoritativeCapabilityGrant grant,
            ShareState share,
            WorkspaceContext context) {
        if (grant.scope()
                == CalendarAuthoritativeCapabilityGrant.Scope.PLAN) {
            if (!"LIVE_WHOLE_PLAN".equals(share.scopeMode())) {
                throw new BusinessException(
                        "CALENDAR_AUTHORITATIVE_SCOPE_EXCEEDS_SHARE",
                        "Plan capability requires a whole-plan editor share");
            }
            return;
        }
        Long matches = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_time_node node_row
                WHERE node_row.id = ? AND node_row.plan_id = ?
                  AND node_row.workspace_id = ?
                  AND node_row.created_by_user_id = ?
                  AND (
                    ? = 'LIVE_WHOLE_PLAN'
                    OR EXISTS (
                        SELECT 1 FROM calendar_share_scope_item item
                        WHERE item.snapshot_id = ?
                          AND item.share_id = ?
                          AND item.target_kind = 'NODE'
                          AND item.node_id = node_row.id))
                """,
                Long.class,
                grant.nodeId(),
                share.planId(),
                context.workspaceId(),
                context.actorId(),
                share.scopeMode(),
                share.scopeSnapshotId(),
                share.id());
        if (matches == null || matches != 1) {
            throw new BusinessException(
                    "CALENDAR_AUTHORITATIVE_SCOPE_EXCEEDS_SHARE",
                    "Node capability must stay inside the editor share scope");
        }
    }

    private ShareState findOwnedShare(
            UUID shareId,
            WorkspaceContext context,
            boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<ShareState> rows = jdbc.query(
                """
                SELECT id, plan_id, grantee_user_id, permission,
                       scope_mode, status, share_revision,
                       current_scope_snapshot_id
                FROM calendar_share
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """
                        + lockClause,
                (resultSet, rowNumber) -> new ShareState(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("plan_id", UUID.class),
                        resultSet.getObject("grantee_user_id", UUID.class),
                        resultSet.getString("permission"),
                        resultSet.getString("scope_mode"),
                        resultSet.getString("status"),
                        resultSet.getLong("share_revision"),
                        resultSet.getObject(
                                "current_scope_snapshot_id",
                                UUID.class)),
                shareId,
                context.workspaceId(),
                context.actorId());
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar share", "authoritative share");
        }
        return rows.getFirst();
    }

    private CapabilityState findCapability(
            UUID capabilityId,
            WorkspaceContext context,
            boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<CapabilityState> rows = jdbc.query(
                """
                SELECT id, share_id, plan_id, node_id,
                       capability_scope, status, capability_revision,
                       revoke_request_hash
                FROM calendar_authoritative_editor_capability
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """
                        + lockClause,
                (resultSet, rowNumber) -> new CapabilityState(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("share_id", UUID.class),
                        resultSet.getObject("plan_id", UUID.class),
                        resultSet.getObject("node_id", UUID.class),
                        resultSet.getString("capability_scope"),
                        resultSet.getString("status"),
                        resultSet.getLong("capability_revision"),
                        resultSet.getString("revoke_request_hash")),
                capabilityId,
                context.workspaceId(),
                context.actorId());
        if (rows.isEmpty()) {
            throw new NotFoundException(
                    "Calendar authoritative capability",
                    "requested capability");
        }
        return rows.getFirst();
    }

    private CalendarAuthoritativeCapabilityView findByGrantRequest(
            String requestHash, WorkspaceContext context) {
        List<CalendarAuthoritativeCapabilityView> rows = jdbc.query(
                """
                SELECT id, share_id, plan_id, node_id,
                       capability_scope, status, capability_revision
                FROM calendar_authoritative_editor_capability
                WHERE grant_request_hash = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                (resultSet, rowNumber) ->
                        new CalendarAuthoritativeCapabilityView(
                                resultSet.getObject(
                                        "id", UUID.class),
                                resultSet.getObject(
                                        "share_id", UUID.class),
                                resultSet.getObject(
                                        "plan_id", UUID.class),
                                resultSet.getObject(
                                        "node_id", UUID.class),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.valueOf(resultSet.getString(
                                                "capability_scope")),
                                CalendarAuthoritativeCapabilityView
                                        .Status.valueOf(resultSet.getString(
                                                "status")),
                                resultSet.getLong(
                                        "capability_revision")),
                requestHash,
                context.workspaceId(),
                context.actorId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private CalendarAuthoritativeCapabilityView getOwned(
            UUID capabilityId, WorkspaceContext context) {
        return view(findCapability(capabilityId, context, false));
    }

    private static CalendarAuthoritativeCapabilityView view(
            CapabilityState capability) {
        return new CalendarAuthoritativeCapabilityView(
                capability.id(),
                capability.shareId(),
                capability.planId(),
                capability.nodeId(),
                CalendarAuthoritativeCapabilityGrant.Scope.valueOf(
                        capability.scope()),
                CalendarAuthoritativeCapabilityView.Status.valueOf(
                        capability.status()),
                capability.revision());
    }

    private static void requireGrantReplay(
            CalendarAuthoritativeCapabilityView replay,
            CalendarAuthoritativeCapabilityGrant grant) {
        if (!replay.shareId().equals(grant.shareId())
                || replay.scope() != grant.scope()
                || !Objects.equals(replay.nodeId(), grant.nodeId())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another capability");
        }
    }

    private void lockPlan(
            UUID planId, WorkspaceContext context) {
        jdbc.queryForObject(
                """
                SELECT id FROM calendar_plan
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                UUID.class,
                planId,
                context.workspaceId(),
                context.actorId());
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

    private void insertOutbox(
            String eventType,
            String requestHash,
            ShareState share,
            String payload,
            Instant now,
            WorkspaceContext context) {
        jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, NULL, ?, ?, 'PENDING', ?, ?, ?)
                """,
                UUID.randomUUID(),
                requestHash,
                eventType,
                share.id(),
                share.granteeId(),
                payload,
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
    }

    private record ShareState(
            UUID id,
            UUID planId,
            UUID granteeId,
            String permission,
            String scopeMode,
            String status,
            long revision,
            UUID scopeSnapshotId) {}

    private record CapabilityState(
            UUID id,
            UUID shareId,
            UUID planId,
            UUID nodeId,
            String scope,
            String status,
            long revision,
            String revokeRequestHash) {}
}
