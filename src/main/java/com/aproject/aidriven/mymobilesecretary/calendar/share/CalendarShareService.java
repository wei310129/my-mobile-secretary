package com.aproject.aidriven.mymobilesecretary.calendar.share;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarEffectiveOwnerAccess;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarOrganizerRemovalCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarParticipationScope;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarParticipationScopeType;
import com.aproject.aidriven.mymobilesecretary.calendar.participation.CalendarRegistrationLifecycleService;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarShareService {

    private final JdbcTemplate jdbc;
    private final CalendarPlanRepository plans;
    private final CalendarEffectiveOwnerAccess effectiveOwnerAccess;
    private final CalendarRegistrationLifecycleService registrationLifecycle;
    private final Clock clock;

    public CalendarShareService(
            JdbcTemplate jdbc,
            CalendarPlanRepository plans,
            CalendarEffectiveOwnerAccess effectiveOwnerAccess,
            CalendarRegistrationLifecycleService registrationLifecycle,
            Clock clock) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.effectiveOwnerAccess = effectiveOwnerAccess;
        this.registrationLifecycle = registrationLifecycle;
        this.clock = clock;
    }

    public CalendarShareView createViewerShare(
            String requestKey,
            UUID planId,
            UUID granteeUserId,
            long expectedPlanRevision) {
        return grantViewer(
                requestKey,
                planId,
                granteeUserId,
                expectedPlanRevision,
                CalendarShareScope.liveWholePlan());
    }

    public CalendarShareScopePreview previewViewerScope(
            UUID planId,
            long expectedPlanRevision,
            CalendarShareScope scope) {
        WorkspaceContext context = context();
        CalendarEffectiveOwnerAccess.Scope owner =
                effectiveOwnerAccess.requireAndLockPlan(planId, context);
        var plan = plans.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        planId, context.workspaceId(), owner.sourceOwnerId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        plan.requireActiveForMutation();
        if (plan.getRevision() != expectedPlanRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar plan changed; reload before sharing");
        }
        if (scope.mode() == CalendarShareScopeMode.LIVE_WHOLE_PLAN) {
            return new CalendarShareScopePreview(
                    scope, 1, hash(scope.semanticKey()), List.of());
        }
        List<ScopeItem> items = scope.mode()
                        == CalendarShareScopeMode.SELECTED_ACTIVITIES
                ? selectedActivityItems(
                        planId,
                        scope.targetIds(),
                        context,
                        owner.sourceOwnerId())
                : selectedNodeItems(
                        planId,
                        scope.targetIds(),
                        context,
                        owner.sourceOwnerId());
        List<CalendarShareScopePreview.DependencyMinimum> dependencies =
                items.stream()
                        .filter(ScopeItem::dependencyMinimum)
                        .map(item ->
                                new CalendarShareScopePreview.DependencyMinimum(
                                        item.nodeId(),
                                        item.dependentNodeId(),
                                        item.dependencyResolvedTime(),
                                        item.dependencyRevision()))
                        .toList();
        int targetCount = (int) items.stream()
                .filter(item -> !item.dependencyMinimum()
                        && !"ACTIVITY_CONTEXT".equals(item.kind()))
                .count();
        return new CalendarShareScopePreview(
                scope, targetCount, scopeDigest(scope, items), dependencies);
    }

    public CalendarShareView grantViewer(
            String requestKey,
            UUID planId,
            UUID granteeUserId,
            long expectedPlanRevision,
            CalendarShareScope scope) {
        CalendarShareScopePreview preview =
                previewViewerScope(planId, expectedPlanRevision, scope);
        return grantViewer(
                requestKey,
                planId,
                granteeUserId,
                expectedPlanRevision,
                scope,
                preview.digest());
    }

    public CalendarShareView grantViewer(
            String requestKey,
            UUID planId,
            UUID granteeUserId,
            long expectedPlanRevision,
            CalendarShareScope scope,
            String expectedPreviewDigest) {
        WorkspaceContext context = context();
        CalendarEffectiveOwnerAccess.Scope owner =
                effectiveOwnerAccess.requireAndLockPlan(planId, context);
        UUID sourceOwnerId = owner.sourceOwnerId();
        String requestHash = hash(requireKey(requestKey));
        String semanticFingerprint =
                hash(planId + "|" + granteeUserId + "|VIEWER|" + scope.semanticKey());
        String payloadHash = semanticFingerprint;
        CalendarShareView replay =
                findByReceipt(requestHash, payloadHash, context, sourceOwnerId);
        if (replay != null) {
            return replay;
        }
        var plan = plans.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        planId, context.workspaceId(), sourceOwnerId)
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        plan.requireActiveForMutation();
        if (plan.getRevision() != expectedPlanRevision) {
            throw new BusinessException(
                    "STALE_CALENDAR_REVISION",
                    "Calendar plan changed; reload before sharing");
        }
        if (scope.mode() != CalendarShareScopeMode.LIVE_WHOLE_PLAN) {
            List<ScopeItem> previewItems = scope.mode()
                            == CalendarShareScopeMode.SELECTED_ACTIVITIES
                    ? selectedActivityItems(
                            planId,
                            scope.targetIds(),
                            context,
                            sourceOwnerId)
                    : selectedNodeItems(
                            planId,
                            scope.targetIds(),
                            context,
                            sourceOwnerId);
            if (expectedPreviewDigest == null
                    || !scopeDigest(scope, previewItems)
                            .equals(expectedPreviewDigest)) {
                throw new BusinessException(
                        "CALENDAR_SHARE_PREVIEW_STALE",
                        "Calendar share scope changed; preview again before sharing");
            }
        }
        requireActiveMember(granteeUserId, context);
        if (context.actorId().equals(granteeUserId)) {
            throw new BusinessException(
                    "CALENDAR_SHARE_SELF_NOT_ALLOWED",
                    "A calendar plan cannot be shared with its owner");
        }
        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        UUID snapshotId = scope.mode() == CalendarShareScopeMode.LIVE_WHOLE_PLAN
                ? null
                : UUID.randomUUID();
        long scopeRevision =
                scope.mode() == CalendarShareScopeMode.LIVE_WHOLE_PLAN ? 0 : 1;
        int inserted = jdbc.update(
                """
                INSERT INTO calendar_share (
                    id, plan_id, grantee_user_id, permission, scope_mode,
                    status, share_revision, creation_request_hash,
                    creation_payload_hash, created_at, updated_at,
                    workspace_id, created_by_user_id, scope_revision,
                    current_scope_snapshot_id, semantic_fingerprint)
                VALUES (?, ?, ?, 'VIEWER', ?, 'ACTIVE', 1,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                id,
                planId,
                granteeUserId,
                scope.mode().name(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                sourceOwnerId,
                scopeRevision,
                snapshotId,
                semanticFingerprint);
        if (inserted == 1 && snapshotId != null) {
            createSelectedSnapshot(
                    id,
                    snapshotId,
                    planId,
                    granteeUserId,
                    scope,
                    scopeRevision,
                    now,
                    context,
                    sourceOwnerId);
        }
        CalendarShareView result =
                findBySemantic(semanticFingerprint, context, sourceOwnerId);
        if (result == null) {
            result =
                    findByCreationRequest(requestHash, context, sourceOwnerId);
            if (result == null) {
                throw new IllegalStateException(
                        "Calendar share arbitration produced no row");
            }
            requirePayload(
                    result.id(),
                    "creation_payload_hash",
                    payloadHash,
                    context,
                    sourceOwnerId);
        }
        insertReceipt(
                requestHash,
                payloadHash,
                semanticFingerprint,
                result.id(),
                now,
                context,
                sourceOwnerId);
        CalendarShareView receiptResult =
                findByReceipt(
                        requestHash, payloadHash, context, sourceOwnerId);
        if (receiptResult == null || !receiptResult.id().equals(result.id())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another calendar share");
        }
        if (inserted == 1) {
            insertOutbox(
                    hash("SHARE_CREATED|" + result.id() + "|" + scopeRevision),
                    "SHARE_CREATED",
                    result.id(),
                    null,
                    granteeUserId,
                    "Calendar viewer share created",
                    now,
                    context,
                    sourceOwnerId);
        }
        return result;
    }

    public CalendarShareView revoke(
            String requestKey, UUID shareId, long expectedRevision) {
        return revoke(
                requestKey,
                shareId,
                expectedRevision,
                CalendarShareLastAccessResolution.preserve());
    }

    public CalendarShareView revoke(
            String requestKey,
            UUID shareId,
            long expectedRevision,
            CalendarShareLastAccessResolution resolution) {
        WorkspaceContext context = context();
        String requestHash = hash(requireKey(requestKey));
        requireResolution(resolution);
        String payloadHash =
                resolution.action()
                                == CalendarShareLastAccessResolution.Action
                                        .PRESERVE_MINIMUM_ACCESS
                        ? hash(shareId + "|REVOKE")
                        : hash(shareId
                                + "|REVOKE|"
                                + resolution.action()
                                + "|"
                                + resolution.expectedRegistrationRevisions()
                                + "|"
                                + resolution.removalReason().strip());
        lockRequest("calendar-share-lifecycle|" + shareId);
        ShareIdentity identity = shareIdentity(shareId, context);
        UUID sourceOwnerId = effectiveOwnerAccess
                .requireAndLockPlan(identity.planId(), context)
                .sourceOwnerId();
        LockedShare share = lock(shareId, context, sourceOwnerId);
        if ("REVOKED".equals(share.status())) {
            if (requestHash.equals(share.revokeRequestHash())
                    && payloadHash.equals(share.revokePayloadHash())) {
                return lockedView(share);
            }
            throw new BusinessException(
                    "CALENDAR_SHARE_ALREADY_REVOKED",
                    "Calendar share is already revoked");
        }
        if (share.revision() != expectedRevision) {
            throw new BusinessException(
                    "CALENDAR_SHARE_REVISION_CONFLICT",
                    "Calendar share changed; reload before revoking");
        }
        Instant now = Instant.now(clock);
        resolveLastParticipantAccess(
                requestKey,
                requestHash,
                payloadHash,
                share,
                resolution,
                now,
                context,
                sourceOwnerId);
        jdbc.update(
                """
                UPDATE calendar_share
                SET status = 'REVOKED', share_revision = share_revision + 1,
                    revoke_request_hash = ?, revoke_payload_hash = ?,
                    revoked_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE' AND share_revision = ?
                """,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                shareId,
                context.workspaceId(),
                sourceOwnerId,
                expectedRevision);
        jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id, created_by_user_id)
                SELECT gen_random_uuid(),
                       md5(? || grant_row.id::text)
                           || md5(grant_row.id::text || ?),
                       'CONTENT_REVOKED', grant_row.share_id, grant_row.id,
                       grant_row.grantee_user_id,
                       'Calendar content access revoked with its share',
                       'PENDING', ?, grant_row.workspace_id,
                       grant_row.created_by_user_id
                FROM calendar_share_content_grant grant_row
                WHERE grant_row.share_id = ? AND grant_row.workspace_id = ?
                  AND grant_row.created_by_user_id = ?
                  AND grant_row.status = 'ACTIVE'
                ON CONFLICT DO NOTHING
                """,
                requestHash,
                requestHash,
                Timestamp.from(now),
                shareId,
                context.workspaceId(),
                sourceOwnerId);
        jdbc.update(
                """
                UPDATE calendar_share_content_grant
                SET status = 'REVOKED', grant_revision = grant_revision + 1,
                    revoke_request_hash =
                        md5(? || id::text) || md5(id::text || ?),
                    revoke_payload_hash =
                        md5(? || id::text) || md5(id::text || ?),
                    revoked_at = ?, updated_at = ?
                WHERE share_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                """,
                requestHash,
                requestHash,
                payloadHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                shareId,
                context.workspaceId(),
                sourceOwnerId);
        revokeAuthoritativeCapabilities(
                shareId,
                requestHash,
                "SHARE_REVOKED",
                now,
                context,
                sourceOwnerId);
        UUID revokeOutboxId = insertOutbox(
                requestHash,
                "SHARE_REVOKED",
                shareId,
                null,
                share.granteeUserId(),
                "Calendar viewer share revoked",
                now,
                context,
                sourceOwnerId);
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_signal (
                    id, signal_kind, source_outbox_id,
                    authoritative_mutation_id, share_id, plan_id,
                    source_revision, recipient_user_id, payload_text,
                    created_at, workspace_id, created_by_user_id)
                VALUES (?, 'SHARE_REVOKED', ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                revokeOutboxId,
                shareId,
                share.planId(),
                expectedRevision + 1,
                share.granteeUserId(),
                "Calendar source access changed",
                Timestamp.from(now),
                context.workspaceId(),
                sourceOwnerId);
        return getOwned(shareId, context, sourceOwnerId);
    }

    private void resolveLastParticipantAccess(
            String requestKey,
            String requestHash,
            String payloadHash,
            LockedShare share,
            CalendarShareLastAccessResolution resolution,
            Instant now,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        List<ActiveRegistration> affected = jdbc.query(
                        """
                        SELECT registration.id,
                               registration.participation_id,
                               registration.plan_id,
                               registration.activity_id,
                               registration.target_scope,
                               registration.registration_revision
                        FROM calendar_registration registration
                        WHERE registration.plan_id = ?
                          AND registration.workspace_id = ?
                          AND registration.created_by_user_id = ?
                          AND registration.source_created_by_user_id = ?
                          AND registration.registration_state IN (
                                'COMMITTED', 'WAITLISTED',
                                'APPROVAL_REQUIRED')
                        ORDER BY registration.id
                        FOR UPDATE
                        """,
                        (row, ignored) -> new ActiveRegistration(
                                row.getObject("id", UUID.class),
                                row.getObject(
                                        "participation_id", UUID.class),
                                row.getObject("plan_id", UUID.class),
                                row.getObject("activity_id", UUID.class),
                                row.getString("target_scope"),
                                row.getLong("registration_revision")),
                        share.planId(),
                        context.workspaceId(),
                        share.granteeUserId(),
                        sourceOwnerId)
                .stream()
                .filter(registration -> !hasAlternateAccess(
                        registration, share, context, sourceOwnerId))
                .toList();
        if (resolution.action()
                == CalendarShareLastAccessResolution.Action
                        .REMOVE_PARTICIPANT) {
            Map<UUID, Long> expected =
                    resolution.expectedRegistrationRevisions();
            if (!expected.keySet().equals(affected.stream()
                            .map(ActiveRegistration::id)
                            .collect(java.util.stream.Collectors.toSet()))) {
                throw new BusinessException(
                        "CALENDAR_LAST_ACCESS_PREVIEW_STALE",
                        "Active registrations changed; preview revocation again");
            }
            for (ActiveRegistration registration : affected) {
                CalendarParticipationScope scope =
                        "PLAN".equals(registration.targetScope())
                                ? new CalendarParticipationScope(
                                        CalendarParticipationScopeType.PLAN,
                                        registration.planId())
                                : new CalendarParticipationScope(
                                        CalendarParticipationScopeType.ACTIVITY,
                                        registration.activityId());
                registrationLifecycle.remove(
                        new CalendarOrganizerRemovalCommand(
                                requestKey + "|remove|" + registration.id(),
                                registration.planId(),
                                scope,
                                share.granteeUserId(),
                                expected.get(registration.id()),
                                resolution.removalReason()));
            }
            return;
        }
        for (ActiveRegistration registration : affected) {
            preserveMinimumAccess(
                    registration,
                    requestHash,
                    payloadHash,
                    now,
                    context,
                    share.granteeUserId(),
                    sourceOwnerId);
        }
    }

    private boolean hasAlternateAccess(
            ActiveRegistration registration,
            LockedShare revokedShare,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_share alternate
                WHERE alternate.plan_id = ?
                  AND alternate.id <> ?
                  AND alternate.workspace_id = ?
                  AND alternate.created_by_user_id = ?
                  AND alternate.grantee_user_id = ?
                  AND alternate.status = 'ACTIVE'
                  AND (
                    alternate.scope_mode = 'LIVE_WHOLE_PLAN'
                    OR (
                      ? = 'ACTIVITY'
                      AND alternate.scope_mode =
                            'SELECTED_ACTIVITIES'
                      AND EXISTS (
                        SELECT 1
                        FROM calendar_share_scope_item item
                        WHERE item.snapshot_id =
                                alternate.current_scope_snapshot_id
                          AND item.share_id = alternate.id
                          AND item.activity_id = ?
                          AND item.target_kind = 'ACTIVITY')))
                """,
                Long.class,
                registration.planId(),
                revokedShare.id(),
                context.workspaceId(),
                sourceOwnerId,
                revokedShare.granteeUserId(),
                registration.targetScope(),
                registration.activityId());
        return count != null && count > 0;
    }

    private void preserveMinimumAccess(
            ActiveRegistration registration,
            String requestHash,
            String payloadHash,
            Instant now,
            WorkspaceContext context,
            UUID participantUserId,
            UUID sourceOwnerId) {
        String minimumRequestHash =
                hash(requestHash + "|minimum|" + registration.id());
        String minimumPayloadHash =
                hash(payloadHash + "|minimum|" + registration.id());
        int inserted = jdbc.update(
                """
                INSERT INTO calendar_participant_minimum_access (
                    id, registration_id, participation_id,
                    plan_id, activity_id, access_status,
                    access_revision, source_access_revoked_at,
                    terminated_at, operation_request_hash,
                    operation_payload_hash, created_at, updated_at,
                    workspace_id, created_by_user_id,
                    source_created_by_user_id)
                VALUES (
                    ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, NULL, ?, ?,
                    ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                registration.id(),
                registration.participationId(),
                registration.planId(),
                registration.activityId(),
                Timestamp.from(now),
                minimumRequestHash,
                minimumPayloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                participantUserId,
                sourceOwnerId);
        if (inserted == 1) {
            jdbc.update(
                    """
                    INSERT INTO calendar_registration_outbox (
                        id, event_type, plan_id, activity_id,
                        registration_id, recipient_user_id,
                        operation_request_hash,
                        operation_payload_hash,
                        semantic_identity_hash, payload_text,
                        delivery_status, delivery_attempt_count,
                        next_delivery_attempt_at, delivered_at,
                        last_delivery_failure, created_at, updated_at,
                        workspace_id, created_by_user_id,
                        source_created_by_user_id)
                    VALUES (
                        ?, 'PARTICIPANT_MINIMUM_ACCESS', ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        'PENDING', 0, ?, NULL, NULL, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    UUID.randomUUID(),
                    registration.planId(),
                    registration.activityId(),
                    registration.id(),
                    participantUserId,
                    minimumRequestHash,
                    minimumPayloadHash,
                    hash("MINIMUM_ACCESS|"
                            + registration.id()
                            + "|"
                            + registration.revision()),
                    "Minimum calendar access retained for active participation",
                    Timestamp.from(now),
                    Timestamp.from(now),
                    Timestamp.from(now),
                    context.workspaceId(),
                    sourceOwnerId,
                    sourceOwnerId);
        }
    }

    private static void requireResolution(
            CalendarShareLastAccessResolution resolution) {
        if (resolution == null || resolution.action() == null) {
            throw new IllegalArgumentException(
                    "A last-access resolution is required");
        }
        if (resolution.action()
                        == CalendarShareLastAccessResolution.Action
                                .REMOVE_PARTICIPANT
                && (resolution.removalReason() == null
                        || resolution.removalReason().isBlank()
                        || resolution.removalReason().strip().length()
                                > 500)) {
            throw new IllegalArgumentException(
                    "Participant removal requires a bounded reason");
        }
    }

    public CalendarShareView reviseViewerScope(
            String requestKey,
            UUID shareId,
            long expectedScopeRevision,
            String expectedPreviewDigest) {
        WorkspaceContext context = context();
        String requestHash = hash(requireKey(requestKey));
        String payloadHash = hash(
                shareId
                        + "|REVISE_SCOPE|"
                        + expectedScopeRevision
                        + "|"
                        + expectedPreviewDigest);
        ShareIdentity identity = shareIdentity(shareId, context);
        UUID sourceOwnerId = effectiveOwnerAccess
                .requireAndLockPlan(identity.planId(), context)
                .sourceOwnerId();
        CalendarShareView replay =
                findByReceipt(
                        requestHash, payloadHash, context, sourceOwnerId);
        if (replay != null) {
            return replay;
        }
        plans.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        identity.planId(),
                        context.workspaceId(),
                        sourceOwnerId)
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "shared plan"));
        ScopeShare share =
                scopeShare(shareId, context, sourceOwnerId, true);
        if (!"ACTIVE".equals(share.status())
                || "LIVE_WHOLE_PLAN".equals(share.scopeMode())) {
            throw new BusinessException(
                    "CALENDAR_SHARE_SCOPE_NOT_REVISABLE",
                    "Only an active selected calendar share can be revised");
        }
        if (share.scopeRevision() != expectedScopeRevision) {
            throw new BusinessException(
                    "CALENDAR_SHARE_SCOPE_REVISION_CONFLICT",
                    "Calendar share scope changed; preview again");
        }
        CalendarShareScope scope =
                currentExplicitScope(share, context, sourceOwnerId);
        List<ScopeItem> items = "SELECTED_ACTIVITIES".equals(share.scopeMode())
                ? selectedActivityItems(
                        share.planId(),
                        scope.targetIds(),
                        context,
                        sourceOwnerId)
                : selectedNodeItems(
                        share.planId(),
                        scope.targetIds(),
                        context,
                        sourceOwnerId);
        if (expectedPreviewDigest == null
                || !scopeDigest(scope, items)
                        .equals(expectedPreviewDigest)) {
            throw new BusinessException(
                    "CALENDAR_SHARE_PREVIEW_STALE",
                    "Calendar share scope changed; preview again before sharing");
        }
        long nextScopeRevision = expectedScopeRevision + 1;
        UUID snapshotId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        createSelectedSnapshot(
                share.id(),
                snapshotId,
                share.planId(),
                share.granteeUserId(),
                scope,
                nextScopeRevision,
                now,
                context,
                sourceOwnerId);
        int updated = jdbc.update(
                """
                UPDATE calendar_share
                SET scope_revision = ?, current_scope_snapshot_id = ?,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                  AND scope_revision = ?
                """,
                nextScopeRevision,
                snapshotId,
                Timestamp.from(now),
                share.id(),
                context.workspaceId(),
                sourceOwnerId,
                expectedScopeRevision);
        if (updated != 1) {
            throw new BusinessException(
                    "CALENDAR_SHARE_SCOPE_REVISION_CONFLICT",
                    "Calendar share scope changed; preview again");
        }
        insertReceipt(
                requestHash,
                payloadHash,
                share.semanticFingerprint(),
                share.id(),
                now,
                context,
                sourceOwnerId);
        insertOutbox(
                hash("SCOPE_REVISED|" + share.id() + "|" + nextScopeRevision),
                "SCOPE_REVISED",
                share.id(),
                null,
                share.granteeUserId(),
                "Calendar share scope revised",
                now,
                context,
                sourceOwnerId);
        return getOwned(share.id(), context, sourceOwnerId);
    }

    public CalendarShareView changeRole(CalendarShareRoleChange change) {
        WorkspaceContext context = context();
        String requestHash = hash(requireKey(change.requestKey()));
        lockRequest(requestHash);
        lockRequest("calendar-share-lifecycle|" + change.shareId());
        String payloadHash = hash(change.shareId()
                + "|ROLE_CHANGED|"
                + change.permission()
                + "|"
                + change.expectedShareRevision());
        ShareIdentity identity = shareIdentity(change.shareId(), context);
        UUID sourceOwnerId = effectiveOwnerAccess
                .requireAndLockPlan(identity.planId(), context)
                .sourceOwnerId();
        CalendarShareView replay =
                findByReceipt(
                        requestHash, payloadHash, context, sourceOwnerId);
        if (replay != null) {
            return replay;
        }
        plans.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        identity.planId(),
                        context.workspaceId(),
                        sourceOwnerId)
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "shared plan"));
        ScopeShare share =
                scopeShare(change.shareId(), context, sourceOwnerId, true);
        if (!"ACTIVE".equals(share.status())) {
            throw new BusinessException(
                    "CALENDAR_SHARE_ROLE_NOT_CHANGEABLE",
                    "Only an active calendar share can change role");
        }
        if (share.permission().equals(change.permission().name())) {
            boolean transitionReplay = share.shareRevision()
                            == change.expectedShareRevision() + 1
                    && roleAuditMatches(
                            share.id(),
                            share.shareRevision(),
                            change.permission(),
                            context,
                            sourceOwnerId);
            if (share.shareRevision() != change.expectedShareRevision()
                    && !transitionReplay) {
                throw new BusinessException(
                        "CALENDAR_SHARE_REVISION_CONFLICT",
                        "Calendar share changed; reload before changing role");
            }
            Instant now = Instant.now(clock);
            insertReceipt(
                    requestHash,
                    payloadHash,
                    share.semanticFingerprint(),
                    share.id(),
                    now,
                    context,
                    sourceOwnerId);
            return getOwned(share.id(), context, sourceOwnerId);
        }
        if (share.shareRevision() != change.expectedShareRevision()) {
            throw new BusinessException(
                    "CALENDAR_SHARE_REVISION_CONFLICT",
                    "Calendar share changed; reload before changing role");
        }
        CalendarShareScope explicitScope = "LIVE_WHOLE_PLAN".equals(
                        share.scopeMode())
                ? CalendarShareScope.liveWholePlan()
                : currentExplicitScope(share, context, sourceOwnerId);
        String nextSemanticFingerprint = hash(share.planId()
                + "|"
                + share.granteeUserId()
                + "|"
                + change.permission()
                + "|"
                + explicitScope.semanticKey());
        Long conflicts = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_share
                WHERE semantic_fingerprint = ? AND status = 'ACTIVE'
                  AND workspace_id = ? AND created_by_user_id = ?
                  AND id <> ?
                """,
                Long.class,
                nextSemanticFingerprint,
                context.workspaceId(),
                sourceOwnerId,
                share.id());
        if (conflicts == null || conflicts != 0) {
            throw new BusinessException(
                    "CALENDAR_SHARE_ROLE_CHANGE_CONFLICT",
                    "An equivalent active calendar share already exists");
        }
        Instant now = Instant.now(clock);
        long nextRevision = share.shareRevision() + 1;
        int updated = jdbc.update(
                """
                UPDATE calendar_share
                SET permission = ?, share_revision = ?,
                    semantic_fingerprint = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                  AND share_revision = ?
                """,
                change.permission().name(),
                nextRevision,
                nextSemanticFingerprint,
                Timestamp.from(now),
                share.id(),
                context.workspaceId(),
                sourceOwnerId,
                change.expectedShareRevision());
        if (updated != 1) {
            throw new BusinessException(
                    "CALENDAR_SHARE_REVISION_CONFLICT",
                    "Calendar share changed; reload before changing role");
        }
        if (change.permission() == CalendarSharePermission.VIEWER) {
            revokeAuthoritativeCapabilities(
                    share.id(),
                    requestHash,
                    "ROLE_DOWNGRADED",
                    now,
                    context,
                    sourceOwnerId);
        }
        jdbc.update(
                """
                INSERT INTO calendar_share_role_audit (
                    id, share_id, plan_id, grantee_user_id,
                    previous_permission, current_permission,
                    previous_share_revision, current_share_revision,
                    operation_request_hash, occurred_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                share.id(),
                share.planId(),
                share.granteeUserId(),
                share.permission(),
                change.permission().name(),
                share.shareRevision(),
                nextRevision,
                requestHash,
                Timestamp.from(now),
                context.workspaceId(),
                sourceOwnerId);
        insertReceipt(
                requestHash,
                payloadHash,
                nextSemanticFingerprint,
                share.id(),
                now,
                context,
                sourceOwnerId);
        insertOutbox(
                hash("ROLE_CHANGED|" + share.id() + "|" + nextRevision),
                "ROLE_CHANGED",
                share.id(),
                null,
                share.granteeUserId(),
                "Calendar share role changed",
                now,
                context,
                sourceOwnerId);
        return getOwned(share.id(), context, sourceOwnerId);
    }

    private void revokeAuthoritativeCapabilities(
            UUID shareId,
            String lifecycleRequestHash,
            String cause,
            Instant now,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id, created_by_user_id)
                SELECT gen_random_uuid(),
                       md5(? || capability.id::text)
                           || md5(capability.id::text || ?),
                       'AUTHORITATIVE_CAPABILITY_REVOKED',
                       capability.share_id, NULL,
                       capability.grantee_user_id,
                       'capabilityId=' || capability.id::text
                           || ';capabilityRevision='
                           || (capability.capability_revision + 1)::text
                           || ';cause=' || ?,
                       'PENDING', ?, capability.workspace_id,
                       capability.created_by_user_id
                FROM calendar_authoritative_editor_capability capability
                WHERE capability.share_id = ?
                  AND capability.workspace_id = ?
                  AND capability.created_by_user_id = ?
                  AND capability.status = 'ACTIVE'
                ON CONFLICT DO NOTHING
                """,
                lifecycleRequestHash,
                lifecycleRequestHash,
                cause,
                Timestamp.from(now),
                shareId,
                context.workspaceId(),
                sourceOwnerId);
        jdbc.update(
                """
                UPDATE calendar_authoritative_editor_capability
                SET status = 'REVOKED',
                    capability_revision = capability_revision + 1,
                    revoke_request_hash =
                        md5(? || id::text) || md5(id::text || ?),
                    revoked_at = ?
                WHERE share_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                """,
                lifecycleRequestHash,
                lifecycleRequestHash,
                Timestamp.from(now),
                shareId,
                context.workspaceId(),
                sourceOwnerId);
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

    @Transactional(readOnly = true)
    public CalendarShareView get(UUID shareId) {
        WorkspaceContext context = context();
        ShareIdentity identity = shareIdentity(shareId, context);
        UUID sourceOwnerId =
                effectiveOwnerAccess.require(identity.planId(), context)
                        .sourceOwnerId();
        return getOwned(shareId, context, sourceOwnerId);
    }

    UUID insertOutbox(
            String requestHash,
            String eventType,
            UUID shareId,
            UUID contentGrantId,
            UUID granteeUserId,
            String payload,
            Instant now,
            WorkspaceContext context) {
        UUID sourceOwnerId = effectiveOwnerAccess
                .require(shareIdentity(shareId, context).planId(), context)
                .sourceOwnerId();
        return insertOutbox(
                requestHash,
                eventType,
                shareId,
                contentGrantId,
                granteeUserId,
                payload,
                now,
                context,
                sourceOwnerId);
    }

    private UUID insertOutbox(
            String requestHash,
            String eventType,
            UUID shareId,
            UUID contentGrantId,
            UUID granteeUserId,
            String payload,
            Instant now,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        UUID outboxId = UUID.randomUUID();
        int inserted = jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                outboxId,
                requestHash,
                eventType,
                shareId,
                contentGrantId,
                granteeUserId,
                payload,
                Timestamp.from(now),
                context.workspaceId(),
                sourceOwnerId);
        if (inserted == 1) {
            return outboxId;
        }
        UUID existing = jdbc.queryForObject(
                """
                SELECT id FROM calendar_share_outbox
                WHERE operation_request_hash = ? AND event_type = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                UUID.class,
                requestHash,
                eventType,
                context.workspaceId(),
                sourceOwnerId);
        if (existing == null) {
            throw new IllegalStateException(
                    "Calendar share outbox arbitration produced no row");
        }
        return existing;
    }

    private void createSelectedSnapshot(
            UUID shareId,
            UUID snapshotId,
            UUID planId,
            UUID granteeUserId,
            CalendarShareScope scope,
            long scopeRevision,
            Instant now,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        List<ScopeItem> items = switch (scope.mode()) {
            case SELECTED_ACTIVITIES ->
                    selectedActivityItems(
                            planId,
                            scope.targetIds(),
                            context,
                            sourceOwnerId);
            case SELECTED_NODES ->
                    selectedNodeItems(
                            planId,
                            scope.targetIds(),
                            context,
                            sourceOwnerId);
            case LIVE_WHOLE_PLAN -> throw new IllegalArgumentException(
                    "Whole-plan shares do not create a scope snapshot");
        };
        if (items.isEmpty()) {
            throw new BusinessException(
                    "CALENDAR_SHARE_SCOPE_EMPTY",
                    "Selected calendar share scope contains no visible target");
        }
        jdbc.update(
                """
                INSERT INTO calendar_share_scope_snapshot (
                    id, share_id, plan_id, scope_mode, scope_revision,
                    grantee_user_id, created_at, workspace_id,
                    created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshotId,
                shareId,
                planId,
                scope.mode().name(),
                scopeRevision,
                granteeUserId,
                Timestamp.from(now),
                context.workspaceId(),
                sourceOwnerId);
        for (ScopeItem item : items) {
            jdbc.update(
                    """
                    INSERT INTO calendar_share_scope_item (
                        id, snapshot_id, share_id, plan_id, scope_revision,
                        target_kind, activity_id, node_id, target_version,
                        context_title, dependent_node_id, dependency_minimum,
                        dependency_resolved_time, dependency_revision,
                        grantee_user_id, created_at, workspace_id,
                        created_by_user_id)
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    snapshotId,
                    shareId,
                    planId,
                    scopeRevision,
                    item.kind(),
                    item.activityId(),
                    item.nodeId(),
                    item.targetVersion(),
                    item.contextTitle(),
                    item.dependentNodeId(),
                    item.dependencyMinimum(),
                    item.dependencyResolvedTime() == null
                            ? null
                            : Timestamp.from(item.dependencyResolvedTime()),
                    item.dependencyRevision(),
                    granteeUserId,
                    Timestamp.from(now),
                    context.workspaceId(),
                    sourceOwnerId);
        }
        jdbc.update(
                """
                UPDATE calendar_share_scope_snapshot
                SET sealed_at = ?
                WHERE id = ? AND share_id = ? AND sealed_at IS NULL
                """,
                Timestamp.from(now),
                snapshotId,
                shareId);
    }

    private List<ScopeItem> selectedActivityItems(
            UUID planId,
            List<UUID> activityIds,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        var items = new ArrayList<ScopeItem>();
        for (UUID activityId : activityIds) {
            List<Long> versions = jdbc.query(
                    """
                    SELECT version
                    FROM calendar_activity
                    WHERE id = ? AND plan_id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                    FOR UPDATE
                    """,
                    (row, index) -> row.getLong("version"),
                    activityId,
                    planId,
                    context.workspaceId(),
                    sourceOwnerId);
            if (versions.size() != 1) {
                throw new NotFoundException(
                        "Calendar activity", "selected activity");
            }
            items.add(ScopeItem.activity(activityId, versions.getFirst()));
        }
        List<NodeRow> nodes = jdbc.query(
                """
                SELECT id, activity_id, node_key, revision, expression_kind,
                       base_node_key, resolved_time
                FROM calendar_time_node
                WHERE plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                  AND activity_id = ANY (?::uuid[])
                ORDER BY id
                FOR UPDATE
                """,
                (row, index) -> nodeRow(row),
                planId,
                context.workspaceId(),
                sourceOwnerId,
                activityIds.toArray(UUID[]::new));
        for (NodeRow node : nodes) {
            items.add(ScopeItem.node(node.id(), node.revision()));
        }
        Map<String, NodeRow> capturedByKey = nodes.stream()
                .collect(java.util.stream.Collectors.toMap(
                        NodeRow::nodeKey, node -> node));
        Map<UUID, ScopeItem> dependencies = new HashMap<>();
        for (NodeRow node : nodes) {
            if (!"NODE_OFFSET".equals(node.expressionKind())
                    || capturedByKey.containsKey(node.baseNodeKey())) {
                continue;
            }
            NodeRow base = lockDependencyBase(
                    planId, node.baseNodeKey(), context, sourceOwnerId);
            dependencies.putIfAbsent(
                    base.id(),
                    ScopeItem.dependency(
                            base.id(),
                            node.id(),
                            base.revision(),
                            base.resolvedTime()));
        }
        items.addAll(dependencies.values().stream()
                .sorted((left, right) ->
                        left.nodeId().toString().compareTo(right.nodeId().toString()))
                .toList());
        return List.copyOf(items);
    }

    private List<ScopeItem> selectedNodeItems(
            UUID planId,
            List<UUID> nodeIds,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        var selected = new ArrayList<NodeRow>();
        for (UUID nodeId : nodeIds) {
            List<NodeRow> rows = jdbc.query(
                    """
                    SELECT id, activity_id, node_key, revision, expression_kind,
                           base_node_key, resolved_time
                    FROM calendar_time_node
                    WHERE id = ? AND plan_id = ? AND workspace_id = ?
                      AND created_by_user_id = ?
                    FOR UPDATE
                    """,
                    (row, index) -> nodeRow(row),
                    nodeId,
                    planId,
                    context.workspaceId(),
                    sourceOwnerId);
            if (rows.size() != 1) {
                throw new NotFoundException("Calendar node", "selected node");
            }
            selected.add(rows.getFirst());
        }
        var items = new ArrayList<ScopeItem>();
        Map<UUID, ScopeItem> dependencies = new HashMap<>();
        Map<UUID, ScopeItem> activityContexts = new HashMap<>();
        Map<String, NodeRow> selectedByKey = selected.stream()
                .collect(java.util.stream.Collectors.toMap(
                        NodeRow::nodeKey, node -> node));
        for (NodeRow node : selected) {
            items.add(ScopeItem.node(node.id(), node.revision()));
            if (node.activityId() != null) {
                ActivityLock activity = lockActivityVersion(
                        planId,
                        node.activityId(),
                        context,
                        sourceOwnerId);
                activityContexts.putIfAbsent(
                        node.activityId(),
                        ScopeItem.activityContext(
                                node.activityId(),
                                activity.version(),
                                activity.title()));
            }
            if (!"NODE_OFFSET".equals(node.expressionKind())) {
                continue;
            }
            if (selectedByKey.containsKey(node.baseNodeKey())) {
                continue;
            }
            NodeRow base =
                    lockDependencyBase(
                            planId,
                            node.baseNodeKey(),
                            context,
                            sourceOwnerId);
            dependencies.putIfAbsent(
                    base.id(),
                    ScopeItem.dependency(
                            base.id(),
                            node.id(),
                            base.revision(),
                            base.resolvedTime()));
        }
        items.addAll(dependencies.values().stream()
                .sorted((left, right) ->
                        left.nodeId().toString().compareTo(right.nodeId().toString()))
                .toList());
        items.addAll(activityContexts.values().stream()
                .sorted((left, right) -> left.activityId()
                        .toString()
                        .compareTo(right.activityId().toString()))
                .toList());
        return List.copyOf(items);
    }

    private NodeRow lockDependencyBase(
            UUID planId,
            String baseNodeKey,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        List<NodeRow> bases = jdbc.query(
                """
                SELECT id, activity_id, node_key, revision, expression_kind,
                       base_node_key, resolved_time
                FROM calendar_time_node
                WHERE plan_id = ? AND node_key = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, index) -> nodeRow(row),
                planId,
                baseNodeKey,
                context.workspaceId(),
                sourceOwnerId);
        if (bases.size() != 1
                || "NODE_OFFSET".equals(bases.getFirst().expressionKind())
                || bases.getFirst().resolvedTime() == null) {
            throw new BusinessException(
                    "CALENDAR_SHARE_DEPENDENCY_DISCLOSURE_UNSAFE",
                    "Selected relative node requires an unavailable minimal dependency");
        }
        return bases.getFirst();
    }

    private ActivityLock lockActivityVersion(
            UUID planId,
            UUID activityId,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        List<ActivityLock> versions = jdbc.query(
                """
                SELECT version, title
                FROM calendar_activity
                WHERE id = ? AND plan_id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, index) -> new ActivityLock(
                        row.getLong("version"), row.getString("title")),
                activityId,
                planId,
                context.workspaceId(),
                sourceOwnerId);
        if (versions.size() != 1) {
            throw new NotFoundException(
                    "Calendar activity", "selected node parent");
        }
        return versions.getFirst();
    }

    private void insertReceipt(
            String requestHash,
            String payloadHash,
            String semanticFingerprint,
            UUID shareId,
            Instant now,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        jdbc.update(
                """
                INSERT INTO calendar_share_request_receipt (
                    id, request_hash, payload_hash, semantic_fingerprint,
                    share_id, plan_id, grantee_user_id, created_at,
                    workspace_id, created_by_user_id)
                SELECT ?, ?, ?, ?, share_row.id, share_row.plan_id,
                       share_row.grantee_user_id, ?, share_row.workspace_id,
                       share_row.created_by_user_id
                FROM calendar_share share_row
                WHERE share_row.id = ? AND share_row.workspace_id = ?
                  AND share_row.created_by_user_id = ?
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                requestHash,
                payloadHash,
                semanticFingerprint,
                Timestamp.from(now),
                shareId,
                context.workspaceId(),
                sourceOwnerId);
    }

    private CalendarShareView findByReceipt(
            String requestHash,
            String payloadHash,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        List<ReceiptRow> receipts = jdbc.query(
                """
                SELECT receipt.payload_hash, receipt.share_id
                FROM calendar_share_request_receipt receipt
                WHERE receipt.request_hash = ? AND receipt.workspace_id = ?
                  AND receipt.created_by_user_id = ?
                """,
                (row, index) -> new ReceiptRow(
                        row.getString("payload_hash"),
                        row.getObject("share_id", UUID.class)),
                requestHash,
                context.workspaceId(),
                sourceOwnerId);
        if (receipts.isEmpty()) {
            return null;
        }
        ReceiptRow receipt = receipts.getFirst();
        if (!payloadHash.equals(receipt.payloadHash())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another calendar share");
        }
        return getOwned(receipt.shareId(), context, sourceOwnerId);
    }

    private CalendarShareView findBySemantic(
            String semanticFingerprint,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        return first(
                """
                semantic_fingerprint = ? AND status = 'ACTIVE'
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                semanticFingerprint,
                context.workspaceId(),
                sourceOwnerId);
    }

    private static NodeRow nodeRow(ResultSet row) throws SQLException {
        Timestamp resolved = row.getTimestamp("resolved_time");
        return new NodeRow(
                row.getObject("id", UUID.class),
                row.getObject("activity_id", UUID.class),
                row.getString("node_key"),
                row.getLong("revision"),
                row.getString("expression_kind"),
                row.getString("base_node_key"),
                resolved == null ? null : resolved.toInstant());
    }

    private static String scopeDigest(
            CalendarShareScope scope, List<ScopeItem> items) {
        String material = scope.semanticKey()
                + "|"
                + items.stream()
                        .map(item -> item.kind()
                                + ":"
                                + item.activityId()
                                + ":"
                                + item.nodeId()
                                + ":"
                                + item.targetVersion()
                                + ":"
                                + item.contextTitle()
                                + ":"
                                + item.dependentNodeId()
                                + ":"
                                + item.dependencyResolvedTime()
                                + ":"
                                + item.dependencyRevision())
                        .sorted()
                        .reduce((left, right) -> left + "|" + right)
                        .orElse("");
        return hash(material);
    }

    private CalendarShareScope currentExplicitScope(
            ScopeShare share,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        String targetKind = "SELECTED_ACTIVITIES".equals(share.scopeMode())
                ? "ACTIVITY"
                : "NODE";
        String column = "ACTIVITY".equals(targetKind)
                ? "activity_id"
                : "node_id";
        List<UUID> targetIds = jdbc.queryForList(
                """
                SELECT %s
                FROM calendar_share_scope_item
                WHERE snapshot_id = ? AND share_id = ?
                  AND scope_revision = ? AND target_kind = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                ORDER BY %s
                """
                        .formatted(column, column),
                UUID.class,
                share.snapshotId(),
                share.id(),
                share.scopeRevision(),
                targetKind,
                context.workspaceId(),
                sourceOwnerId);
        return "SELECTED_ACTIVITIES".equals(share.scopeMode())
                ? CalendarShareScope.selectedActivities(targetIds)
                : CalendarShareScope.selectedNodes(targetIds);
    }

    private ScopeShare scopeShare(
            UUID shareId,
            WorkspaceContext context,
            UUID sourceOwnerId,
            boolean lock) {
        List<ScopeShare> rows = jdbc.query(
                """
                SELECT id, plan_id, grantee_user_id, status, permission,
                       share_revision, scope_mode, scope_revision,
                       current_scope_snapshot_id,
                       semantic_fingerprint
                FROM calendar_share
                WHERE id = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                %s
                """
                        .formatted(lock ? "FOR UPDATE" : ""),
                (row, index) -> new ScopeShare(
                        row.getObject("id", UUID.class),
                        row.getObject("plan_id", UUID.class),
                        row.getObject("grantee_user_id", UUID.class),
                        row.getString("status"),
                        row.getString("permission"),
                        row.getLong("share_revision"),
                        row.getString("scope_mode"),
                        row.getLong("scope_revision"),
                        row.getObject(
                                "current_scope_snapshot_id", UUID.class),
                        row.getString("semantic_fingerprint")),
                shareId,
                context.workspaceId(),
                sourceOwnerId);
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar share", "requested share");
        }
        return rows.getFirst();
    }

    private boolean roleAuditMatches(
            UUID shareId,
            long currentRevision,
            CalendarSharePermission permission,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_share_role_audit
                WHERE share_id = ? AND current_share_revision = ?
                  AND current_permission = ? AND workspace_id = ?
                  AND created_by_user_id = ?
                """,
                Long.class,
                shareId,
                currentRevision,
                permission.name(),
                context.workspaceId(),
                sourceOwnerId);
        return count != null && count == 1;
    }

    private void requireActiveMember(
            UUID granteeUserId, WorkspaceContext context) {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM workspace_member member
                JOIN app_user recipient ON recipient.id = member.user_id
                WHERE member.workspace_id = ? AND member.user_id = ?
                  AND recipient.status = 'ACTIVE'
                """,
                Long.class,
                context.workspaceId(),
                granteeUserId);
        if (count == null || count != 1L) {
            throw new NotFoundException("Workspace member", "requested recipient");
        }
    }

    private LockedShare lock(
            UUID id, WorkspaceContext context, UUID sourceOwnerId) {
        List<LockedShare> rows = jdbc.query(
                """
                SELECT id, plan_id, grantee_user_id, status, share_revision,
                       revoke_request_hash, revoke_payload_hash
                FROM calendar_share
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, index) -> locked(row),
                id,
                context.workspaceId(),
                sourceOwnerId);
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar share", "requested share");
        }
        return rows.getFirst();
    }

    private CalendarShareView getOwned(
            UUID id, WorkspaceContext context, UUID sourceOwnerId) {
        List<CalendarShareView> rows = jdbc.query(
                """
                SELECT id, plan_id, grantee_user_id, status, share_revision
                FROM calendar_share
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                CalendarShareService::view,
                id,
                context.workspaceId(),
                sourceOwnerId);
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar share", "requested share");
        }
        return rows.getFirst();
    }

    private CalendarShareView findByCreationRequest(
            String requestHash,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        return first(
                """
                creation_request_hash = ?
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                requestHash,
                context.workspaceId(),
                sourceOwnerId);
    }

    private CalendarShareView findActive(
            UUID planId,
            UUID grantee,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        return first(
                """
                plan_id = ? AND grantee_user_id = ? AND status = 'ACTIVE'
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                planId,
                grantee,
                context.workspaceId(),
                sourceOwnerId);
    }

    private CalendarShareView first(String predicate, Object... args) {
        List<CalendarShareView> rows = jdbc.query(
                """
                SELECT id, plan_id, grantee_user_id, status, share_revision
                FROM calendar_share WHERE %s LIMIT 2
                """
                        .formatted(predicate),
                CalendarShareService::view,
                args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ShareIdentity shareIdentity(
            UUID shareId, WorkspaceContext context) {
        List<ShareIdentity> rows = jdbc.query(
                """
                SELECT id, plan_id
                FROM calendar_share
                WHERE id = ? AND workspace_id = ?
                """,
                (row, ignored) -> new ShareIdentity(
                        row.getObject("id", UUID.class),
                        row.getObject("plan_id", UUID.class)),
                shareId,
                context.workspaceId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar share", "requested share");
        }
        return rows.getFirst();
    }

    private void requirePayload(
            UUID id,
            String column,
            String expected,
            WorkspaceContext context,
            UUID sourceOwnerId) {
        String actual = jdbc.queryForObject(
                "SELECT "
                        + column
                        + """
                         FROM calendar_share
                         WHERE id = ? AND workspace_id = ?
                           AND created_by_user_id = ?
                        """,
                String.class,
                id,
                context.workspaceId(),
                sourceOwnerId);
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another calendar share");
        }
    }

    private static CalendarShareView view(ResultSet row, int index)
            throws SQLException {
        return new CalendarShareView(
                row.getObject("id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getObject("grantee_user_id", UUID.class),
                CalendarShareView.Status.valueOf(row.getString("status")),
                row.getLong("share_revision"));
    }

    private static CalendarShareView lockedView(LockedShare share) {
        return new CalendarShareView(
                share.id(),
                share.planId(),
                share.granteeUserId(),
                CalendarShareView.Status.valueOf(share.status()),
                share.revision());
    }

    private static LockedShare locked(ResultSet row) throws SQLException {
        return new LockedShare(
                row.getObject("id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getObject("grantee_user_id", UUID.class),
                row.getString("status"),
                row.getLong("share_revision"),
                row.getString("revoke_request_hash"),
                row.getString("revoke_payload_hash"));
    }

    static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Calendar share requires tenant scope");
        }
        return context;
    }

    static String requireKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException("A bounded request key is required");
        }
        return value.strip();
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record LockedShare(
            UUID id,
            UUID planId,
            UUID granteeUserId,
            String status,
            long revision,
            String revokeRequestHash,
            String revokePayloadHash) {}

    private record ActiveRegistration(
            UUID id,
            UUID participationId,
            UUID planId,
            UUID activityId,
            String targetScope,
            long revision) {}

    private record ReceiptRow(String payloadHash, UUID shareId) {}

    private record ShareIdentity(UUID id, UUID planId) {}

    private record ActivityLock(long version, String title) {}

    private record ScopeShare(
            UUID id,
            UUID planId,
            UUID granteeUserId,
            String status,
            String permission,
            long shareRevision,
            String scopeMode,
            long scopeRevision,
            UUID snapshotId,
            String semanticFingerprint) {}

    private record NodeRow(
            UUID id,
            UUID activityId,
            String nodeKey,
            long revision,
            String expressionKind,
            String baseNodeKey,
            Instant resolvedTime) {}

    private record ScopeItem(
            String kind,
            UUID activityId,
            UUID nodeId,
            long targetVersion,
            String contextTitle,
            UUID dependentNodeId,
            boolean dependencyMinimum,
            Instant dependencyResolvedTime,
            Long dependencyRevision) {

        private static ScopeItem activity(UUID id, long version) {
            return new ScopeItem(
                    "ACTIVITY",
                    id,
                    null,
                    version,
                    null,
                    null,
                    false,
                    null,
                    null);
        }

        private static ScopeItem node(UUID id, long revision) {
            return new ScopeItem(
                    "NODE",
                    null,
                    id,
                    revision,
                    null,
                    null,
                    false,
                    null,
                    null);
        }

        private static ScopeItem activityContext(
                UUID id, long version, String title) {
            return new ScopeItem(
                    "ACTIVITY_CONTEXT",
                    id,
                    null,
                    version,
                    title,
                    null,
                    false,
                    null,
                    null);
        }

        private static ScopeItem dependency(
                UUID id,
                UUID dependentNodeId,
                long revision,
                Instant resolvedTime) {
            return new ScopeItem(
                    "DEPENDENCY_MINIMUM",
                    null,
                    id,
                    revision,
                    null,
                    dependentNodeId,
                    true,
                    resolvedTime,
                    revision);
        }
    }
}
