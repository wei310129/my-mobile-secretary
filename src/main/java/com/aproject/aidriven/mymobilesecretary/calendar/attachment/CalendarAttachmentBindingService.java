package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.media.persistence.StoredMediaRepository;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarAttachmentBindingService {

    private final JdbcTemplate jdbc;
    private final StoredMediaRepository media;
    private final CalendarPlanRepository plans;
    private final CalendarActivityRepository activities;
    private final CalendarTimeNodeRepository nodes;
    private final Clock clock;

    public CalendarAttachmentBindingService(
            JdbcTemplate jdbc,
            StoredMediaRepository media,
            CalendarPlanRepository plans,
            CalendarActivityRepository activities,
            CalendarTimeNodeRepository nodes,
            Clock clock) {
        this.jdbc = jdbc;
        this.media = media;
        this.plans = plans;
        this.activities = activities;
        this.nodes = nodes;
        this.clock = clock;
    }

    public CalendarAttachmentBindingView bind(
            String requestKey,
            long mediaId,
            CalendarAttachmentTarget target,
            String displayName,
            int displayOrder) {
        WorkspaceContext context = context();
        media.findByIdAndWorkspaceIdAndCreatedByUserIdAndStatus(
                        mediaId,
                        context.workspaceId(),
                        context.actorId(),
                        StoredMedia.Status.AVAILABLE)
                .orElseThrow(() -> new NotFoundException("StoredMedia", "requested media"));
        authorizeTarget(target, context);
        String safeName = requireDisplayName(displayName);
        if (displayOrder < 0) {
            throw new IllegalArgumentException("display order must not be negative");
        }
        String requestHash = hash(requireRequestKey(requestKey));
        String payloadHash =
                hash(mediaId + "|" + target + "|" + safeName + "|" + displayOrder);
        CalendarAttachmentBindingView replay = findByRequest(requestHash, context);
        if (replay != null) {
            requirePayload(replay.id(), payloadHash);
            return replay;
        }
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                INSERT INTO calendar_attachment_binding (
                    id, media_id, target_kind, plan_id, activity_id, node_id,
                    display_name, display_order, status, binding_revision,
                    creation_request_hash, creation_payload_hash,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                mediaId,
                target.kind().name(),
                target.planId(),
                target.activityId(),
                target.nodeId(),
                safeName,
                displayOrder,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        CalendarAttachmentBindingView result = findByRequest(requestHash, context);
        if (result == null) {
            result = findActive(mediaId, target, context);
        }
        if (result == null) {
            throw new IllegalStateException("Attachment binding arbitration produced no row");
        }
        CalendarAttachmentBindingView byRequest =
                findByRequest(requestHash, context);
        if (byRequest != null) {
            requirePayload(byRequest.id(), payloadHash);
        } else if (!result.displayName().equals(safeName)
                || result.displayOrder() != displayOrder) {
            throw new BusinessException(
                    "CALENDAR_ATTACHMENT_ALREADY_BOUND",
                    "Attachment is already bound with different display metadata");
        }
        return result;
    }

    public CalendarAttachmentBindingView unlink(UUID bindingId, long expectedRevision) {
        WorkspaceContext context = context();
        int changed = jdbc.update(
                """
                UPDATE calendar_attachment_binding
                SET status = 'UNLINKED',
                    binding_revision = binding_revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE' AND binding_revision = ?
                """,
                Timestamp.from(Instant.now(clock)),
                bindingId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) {
            throw new BusinessException(
                    "ATTACHMENT_BINDING_REVISION_CONFLICT",
                    "Attachment binding changed; reload before unlinking");
        }
        return get(bindingId);
    }

    public CalendarAttachmentBindingView replace(
            String requestKey,
            UUID bindingId,
            long replacementMediaId,
            long expectedRevision) {
        WorkspaceContext context = context();
        String requestHash = hash(requireRequestKey(requestKey));
        String payloadHash =
                hash(bindingId + "|" + replacementMediaId + "|REPLACE");
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                requestHash);
        Replacement replay = findReplacement(requestHash, context);
        if (replay != null) {
            requireReplacementPayload(replay, payloadHash);
            return get(replay.newBindingId());
        }
        media.findByIdAndWorkspaceIdAndCreatedByUserIdAndStatus(
                        replacementMediaId,
                        context.workspaceId(),
                        context.actorId(),
                        StoredMedia.Status.AVAILABLE)
                .orElseThrow(() ->
                        new NotFoundException("StoredMedia", "replacement media"));
        LockedBinding binding = lock(bindingId, context);
        if (binding.status() != CalendarAttachmentBindingView.Status.ACTIVE) {
            throw new BusinessException(
                    "CALENDAR_ATTACHMENT_NOT_ACTIVE",
                    "Only an active attachment can be replaced");
        }
        if (binding.revision() != expectedRevision) {
            throw new BusinessException(
                    "ATTACHMENT_BINDING_REVISION_CONFLICT",
                    "Attachment binding changed; reload before replacement");
        }
        if (binding.mediaId() == replacementMediaId) {
            throw new BusinessException(
                    "CALENDAR_ATTACHMENT_REPLACEMENT_UNCHANGED",
                    "Replacement media must differ from the current media");
        }
        authorizeTarget(binding.target(), context);
        if (findActive(replacementMediaId, binding.target(), context) != null) {
            throw new BusinessException(
                    "CALENDAR_ATTACHMENT_REPLACEMENT_ALREADY_ACTIVE",
                    "Replacement media is already active on this target");
        }
        UUID replacementBindingId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        int changed = jdbc.update(
                """
                UPDATE calendar_attachment_binding
                SET status = 'REPLACED',
                    binding_revision = binding_revision + 1,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE' AND binding_revision = ?
                """,
                Timestamp.from(now),
                bindingId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) {
            throw new BusinessException(
                    "ATTACHMENT_BINDING_REVISION_CONFLICT",
                    "Attachment binding changed during replacement");
        }
        jdbc.update(
                """
                INSERT INTO calendar_attachment_binding (
                    id, media_id, target_kind, plan_id, activity_id, node_id,
                    display_name, display_order, status, binding_revision,
                    creation_request_hash, creation_payload_hash,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                """,
                replacementBindingId,
                replacementMediaId,
                binding.target().kind().name(),
                binding.target().planId(),
                binding.target().activityId(),
                binding.target().nodeId(),
                binding.displayName(),
                binding.displayOrder(),
                binding.revision() + 1,
                hash("REPLACEMENT_BINDING|" + requestHash),
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        jdbc.update(
                """
                INSERT INTO calendar_attachment_replacement (
                    id, request_hash, payload_hash, old_binding_id,
                    new_binding_id, created_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                requestHash,
                payloadHash,
                bindingId,
                replacementBindingId,
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        return get(replacementBindingId);
    }

    @Transactional(readOnly = true)
    public CalendarAttachmentBindingView get(UUID bindingId) {
        WorkspaceContext context = context();
        List<CalendarAttachmentBindingView> rows = jdbc.query(
                """
                SELECT id, media_id, target_kind, plan_id, display_name,
                       display_order, status, binding_revision
                FROM calendar_attachment_binding
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                CalendarAttachmentBindingService::view,
                bindingId,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar attachment binding", "requested binding");
        }
        return rows.getFirst();
    }

    private void authorizeTarget(
            CalendarAttachmentTarget target, WorkspaceContext context) {
        var plan = plans.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        target.planId(), context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        plan.requireActiveForMutation();
        if (target.kind() == CalendarAttachmentTarget.TargetKind.ACTIVITY) {
            var activity = activities.findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.activityId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar activity", "requested activity"));
            if (!activity.getPlanId().equals(target.planId())) {
                throw new IllegalArgumentException("Activity belongs to another plan");
            }
        } else if (target.kind() == CalendarAttachmentTarget.TargetKind.NODE) {
            var node = nodes.findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.nodeId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar node", "requested node"));
            if (!node.getPlanId().equals(target.planId())) {
                throw new IllegalArgumentException("Node belongs to another plan");
            }
        }
    }

    private CalendarAttachmentBindingView findByRequest(
            String hash, WorkspaceContext context) {
        return one(
                """
                creation_request_hash = ?
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                hash,
                context.workspaceId(),
                context.actorId());
    }

    private CalendarAttachmentBindingView findActive(
            long mediaId, CalendarAttachmentTarget target, WorkspaceContext context) {
        return one(
                """
                media_id = ? AND target_kind = ? AND plan_id = ?
                AND activity_id IS NOT DISTINCT FROM ?
                AND node_id IS NOT DISTINCT FROM ?
                AND status = 'ACTIVE'
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                mediaId,
                target.kind().name(),
                target.planId(),
                target.activityId(),
                target.nodeId(),
                context.workspaceId(),
                context.actorId());
    }

    private CalendarAttachmentBindingView one(String predicate, Object... arguments) {
        List<CalendarAttachmentBindingView> rows = jdbc.query(
                """
                SELECT id, media_id, target_kind, plan_id, display_name,
                       display_order, status, binding_revision
                FROM calendar_attachment_binding
                WHERE %s
                LIMIT 2
                """
                        .formatted(predicate),
                CalendarAttachmentBindingService::view,
                arguments);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requirePayload(UUID id, String expected) {
        String actual = jdbc.queryForObject(
                """
                SELECT creation_payload_hash
                FROM calendar_attachment_binding WHERE id = ?
                """,
                String.class,
                id);
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another attachment binding");
        }
    }

    private LockedBinding lock(UUID bindingId, WorkspaceContext context) {
        List<LockedBinding> rows = jdbc.query(
                """
                SELECT media_id, target_kind, plan_id, activity_id, node_id,
                       display_name, display_order, status, binding_revision
                FROM calendar_attachment_binding
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, index) -> new LockedBinding(
                        row.getLong("media_id"),
                        new CalendarAttachmentTarget(
                                CalendarAttachmentTarget.TargetKind.valueOf(
                                        row.getString("target_kind")),
                                row.getObject("plan_id", UUID.class),
                                row.getObject("activity_id", UUID.class),
                                row.getObject("node_id", UUID.class)),
                        row.getString("display_name"),
                        row.getInt("display_order"),
                        CalendarAttachmentBindingView.Status.valueOf(
                                row.getString("status")),
                        row.getLong("binding_revision")),
                bindingId,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar attachment binding", "requested binding");
        }
        return rows.getFirst();
    }

    private Replacement findReplacement(
            String requestHash, WorkspaceContext context) {
        List<Replacement> rows = jdbc.query(
                """
                SELECT payload_hash, new_binding_id
                FROM calendar_attachment_replacement
                WHERE request_hash = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                (row, index) -> new Replacement(
                        row.getString("payload_hash"),
                        row.getObject("new_binding_id", UUID.class)),
                requestHash,
                context.workspaceId(),
                context.actorId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static void requireReplacementPayload(
            Replacement replay, String expectedPayloadHash) {
        if (!expectedPayloadHash.equals(replay.payloadHash())) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The replacement request was reused for another payload");
        }
    }

    @Transactional(readOnly = true)
    public CalendarAttachmentBindingView findUniqueActive(
            CalendarAttachmentTarget target, String displayName) {
        return findUniqueActiveForConversation(target, displayName)
                .orElseThrow(() -> new NotFoundException(
                        "Calendar attachment binding",
                        "unique active attachment"));
    }

    @Transactional(readOnly = true)
    public Optional<CalendarAttachmentBindingView>
            findUniqueActiveForConversation(
                    CalendarAttachmentTarget target, String displayName) {
        WorkspaceContext context = context();
        String safeName = requireDisplayName(displayName);
        List<CalendarAttachmentBindingView> rows = jdbc.query(
                """
                SELECT id, media_id, target_kind, plan_id, display_name,
                       display_order, status, binding_revision
                FROM calendar_attachment_binding
                WHERE target_kind = ? AND plan_id = ?
                  AND activity_id IS NOT DISTINCT FROM ?
                  AND node_id IS NOT DISTINCT FROM ?
                  AND lower(btrim(display_name)) = lower(btrim(?))
                  AND status = 'ACTIVE'
                  AND workspace_id = ? AND created_by_user_id = ?
                ORDER BY updated_at DESC, id
                LIMIT 2
                """,
                CalendarAttachmentBindingService::view,
                target.kind().name(),
                target.planId(),
                target.activityId(),
                target.nodeId(),
                safeName,
                context.workspaceId(),
                context.actorId());
        return rows.size() == 1
                ? Optional.of(rows.getFirst())
                : Optional.empty();
    }

    private static CalendarAttachmentBindingView view(
            ResultSet row, int index) throws SQLException {
        return new CalendarAttachmentBindingView(
                row.getObject("id", UUID.class),
                row.getLong("media_id"),
                CalendarAttachmentTarget.TargetKind.valueOf(
                        row.getString("target_kind")),
                row.getObject("plan_id", UUID.class),
                row.getString("display_name"),
                row.getInt("display_order"),
                CalendarAttachmentBindingView.Status.valueOf(row.getString("status")),
                row.getLong("binding_revision"));
    }

    private static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Attachment binding requires tenant scope");
        }
        return context;
    }

    private static String requireRequestKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException("A bounded request key is required");
        }
        return value.strip();
    }

    private static String requireDisplayName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Attachment display name is required");
        }
        String safe = value.strip();
        return safe.length() <= 255 ? safe : safe.substring(0, 255);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record LockedBinding(
            long mediaId,
            CalendarAttachmentTarget target,
            String displayName,
            int displayOrder,
            CalendarAttachmentBindingView.Status status,
            long revision) {}

    private record Replacement(String payloadHash, UUID newBindingId) {}
}
