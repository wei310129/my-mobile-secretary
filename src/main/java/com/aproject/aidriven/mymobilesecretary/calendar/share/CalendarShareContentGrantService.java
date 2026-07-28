package com.aproject.aidriven.mymobilesecretary.calendar.share;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaObjectStorage;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
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

@Service
@Transactional
public class CalendarShareContentGrantService {

    private final JdbcTemplate jdbc;
    private final MediaObjectStorage objectStorage;
    private final CalendarShareService shares;
    private final Clock clock;

    public CalendarShareContentGrantService(
            JdbcTemplate jdbc,
            MediaObjectStorage objectStorage,
            CalendarShareService shares,
            Clock clock) {
        this.jdbc = jdbc;
        this.objectStorage = objectStorage;
        this.shares = shares;
        this.clock = clock;
    }

    public CalendarContentGrantView grantAttachment(
            String requestKey, UUID shareId, UUID attachmentBindingId) {
        return grant(
                requestKey,
                shareId,
                CalendarContentGrantView.ContentKind.ATTACHMENT,
                attachmentBindingId);
    }

    public CalendarContentGrantView grantKnowledgeExcerpt(
            String requestKey, UUID shareId, UUID excerptId) {
        return grant(
                requestKey,
                shareId,
                CalendarContentGrantView.ContentKind.KNOWLEDGE_EXCERPT,
                excerptId);
    }

    public CalendarContentGrantView revoke(
            String requestKey, UUID grantId, long expectedRevision) {
        WorkspaceContext context = CalendarShareService.context();
        String requestHash = CalendarShareService.hash(
                CalendarShareService.requireKey(requestKey));
        String payloadHash = CalendarShareService.hash(grantId + "|REVOKE");
        LockedGrant grant = lockGrant(grantId, context);
        if ("REVOKED".equals(grant.status())) {
            if (requestHash.equals(grant.revokeRequestHash())
                    && payloadHash.equals(grant.revokePayloadHash())) {
                return lockedView(grant);
            }
            throw new BusinessException(
                    "CALENDAR_CONTENT_ALREADY_REVOKED",
                    "Calendar content access is already revoked");
        }
        if (grant.revision() != expectedRevision) {
            throw new BusinessException(
                    "CALENDAR_CONTENT_GRANT_REVISION_CONFLICT",
                    "Calendar content access changed; reload before revoking");
        }
        Instant now = Instant.now(clock);
        jdbc.update(
                """
                UPDATE calendar_share_content_grant
                SET status = 'REVOKED', grant_revision = grant_revision + 1,
                    revoke_request_hash = ?, revoke_payload_hash = ?,
                    revoked_at = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'ACTIVE' AND grant_revision = ?
                """,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                grantId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        shares.insertOutbox(
                requestHash,
                "CONTENT_REVOKED",
                grant.shareId(),
                grantId,
                grant.granteeUserId(),
                "Calendar content access revoked",
                now,
                context);
        return getOwned(grantId, context);
    }

    @Transactional(readOnly = true)
    public SharedKnowledgeExcerptView readKnowledgeExcerpt(UUID grantId) {
        WorkspaceContext context = CalendarShareService.context();
        List<SharedKnowledgeExcerptView> rows = jdbc.query(
                """
                SELECT excerpt.version_number, excerpt.snapshot_title,
                       excerpt.snapshot_text
                FROM calendar_share_content_grant grant_row
                JOIN calendar_share share
                  ON share.id = grant_row.share_id
                 AND share.plan_id = grant_row.plan_id
                 AND share.workspace_id = grant_row.workspace_id
                 AND share.created_by_user_id = grant_row.created_by_user_id
                 AND share.grantee_user_id = grant_row.grantee_user_id
                JOIN calendar_knowledge_excerpt excerpt
                  ON excerpt.id = grant_row.knowledge_excerpt_id
                 AND excerpt.plan_id = grant_row.plan_id
                 AND excerpt.workspace_id = grant_row.workspace_id
                 AND excerpt.created_by_user_id = grant_row.created_by_user_id
                JOIN workspace_member member
                  ON member.workspace_id = grant_row.workspace_id
                 AND member.user_id = grant_row.grantee_user_id
                JOIN app_user recipient ON recipient.id = member.user_id
                WHERE grant_row.id = ?
                  AND grant_row.content_kind = 'KNOWLEDGE_EXCERPT'
                  AND grant_row.status = 'ACTIVE'
                  AND grant_row.workspace_id = ?
                  AND grant_row.grantee_user_id = ?
                  AND share.status = 'ACTIVE'
                  AND share.grantee_user_id = ?
                  AND excerpt.status = 'APPROVED'
                  AND recipient.status = 'ACTIVE'
                """,
                (row, index) -> new SharedKnowledgeExcerptView(
                        row.getInt("version_number"),
                        row.getString("snapshot_title"),
                        row.getString("snapshot_text")),
                grantId,
                context.workspaceId(),
                context.actorId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Shared calendar knowledge", "requested content");
        }
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public SharedAttachmentContent readAttachment(UUID grantId) {
        WorkspaceContext context = CalendarShareService.context();
        List<AttachmentRow> rows = jdbc.query(
                """
                SELECT binding.display_name,
                       media.media_type, media.media_kind, media.storage_key
                FROM calendar_share_content_grant grant_row
                JOIN calendar_share share
                  ON share.id = grant_row.share_id
                 AND share.plan_id = grant_row.plan_id
                 AND share.workspace_id = grant_row.workspace_id
                 AND share.created_by_user_id = grant_row.created_by_user_id
                 AND share.grantee_user_id = grant_row.grantee_user_id
                JOIN calendar_attachment_binding binding
                  ON binding.id = grant_row.attachment_binding_id
                 AND binding.media_id = grant_row.media_id
                 AND binding.plan_id = grant_row.plan_id
                 AND binding.workspace_id = grant_row.workspace_id
                 AND binding.created_by_user_id = grant_row.created_by_user_id
                JOIN stored_media media
                  ON media.id = grant_row.media_id
                 AND media.workspace_id = grant_row.workspace_id
                 AND media.created_by_user_id = grant_row.created_by_user_id
                JOIN workspace_member member
                  ON member.workspace_id = grant_row.workspace_id
                 AND member.user_id = grant_row.grantee_user_id
                JOIN app_user recipient ON recipient.id = member.user_id
                WHERE grant_row.id = ?
                  AND grant_row.content_kind = 'ATTACHMENT'
                  AND grant_row.status = 'ACTIVE'
                  AND grant_row.workspace_id = ?
                  AND grant_row.grantee_user_id = ?
                  AND share.status = 'ACTIVE'
                  AND share.grantee_user_id = ?
                  AND binding.status = 'ACTIVE'
                  AND media.status = 'AVAILABLE'
                  AND recipient.status = 'ACTIVE'
                """,
                (row, index) -> new AttachmentRow(
                        row.getString("display_name"),
                        row.getString("media_type"),
                        "IMAGE".equals(row.getString("media_kind")),
                        row.getString("storage_key")),
                grantId,
                context.workspaceId(),
                context.actorId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Shared calendar attachment", "requested content");
        }
        AttachmentRow row = rows.getFirst();
        return new SharedAttachmentContent(
                row.displayName(),
                row.mediaType(),
                row.image(),
                objectStorage.read(row.storageKey()));
    }

    private CalendarContentGrantView grant(
            String requestKey,
            UUID shareId,
            CalendarContentGrantView.ContentKind kind,
            UUID contentId) {
        WorkspaceContext context = CalendarShareService.context();
        String requestHash = CalendarShareService.hash(
                CalendarShareService.requireKey(requestKey));
        String payloadHash =
                CalendarShareService.hash(shareId + "|" + kind + "|" + contentId);
        CalendarContentGrantView replay =
                findByCreationRequest(requestHash, context);
        if (replay != null) {
            requirePayload(replay.id(), payloadHash);
            return replay;
        }
        LockedShare share = lockActiveShare(shareId, context);
        Content content = requireContent(kind, contentId, share, context);
        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_share_content_grant (
                    id, share_id, plan_id, content_kind,
                    attachment_binding_id, knowledge_excerpt_id, media_id,
                    grantee_user_id, status, grant_revision,
                    creation_request_hash, creation_payload_hash,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1,
                        ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                id,
                share.id(),
                share.planId(),
                kind.name(),
                kind == CalendarContentGrantView.ContentKind.ATTACHMENT
                        ? contentId
                        : null,
                kind == CalendarContentGrantView.ContentKind.KNOWLEDGE_EXCERPT
                        ? contentId
                        : null,
                content.mediaId(),
                share.granteeUserId(),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        CalendarContentGrantView result =
                findByCreationRequest(requestHash, context);
        if (result == null) {
            result = findActive(shareId, kind, contentId, context);
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Calendar content grant arbitration produced no row");
        }
        shares.insertOutbox(
                requestHash,
                "CONTENT_GRANTED",
                shareId,
                result.id(),
                share.granteeUserId(),
                "Calendar content access granted",
                now,
                context);
        return result;
    }

    private LockedShare lockActiveShare(
            UUID shareId, WorkspaceContext context) {
        List<LockedShare> rows = jdbc.query(
                """
                SELECT share.id, share.plan_id, share.grantee_user_id
                FROM calendar_share share
                JOIN workspace_member member
                  ON member.workspace_id = share.workspace_id
                 AND member.user_id = share.grantee_user_id
                JOIN app_user recipient ON recipient.id = member.user_id
                WHERE share.id = ? AND share.workspace_id = ?
                  AND share.created_by_user_id = ?
                  AND share.status = 'ACTIVE'
                  AND recipient.status = 'ACTIVE'
                FOR UPDATE OF share
                """,
                (row, index) -> new LockedShare(
                        row.getObject("id", UUID.class),
                        row.getObject("plan_id", UUID.class),
                        row.getObject("grantee_user_id", UUID.class)),
                shareId,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar share", "requested share");
        }
        return rows.getFirst();
    }

    private Content requireContent(
            CalendarContentGrantView.ContentKind kind,
            UUID contentId,
            LockedShare share,
            WorkspaceContext context) {
        if (kind == CalendarContentGrantView.ContentKind.ATTACHMENT) {
            List<Content> rows = jdbc.query(
                    """
                    SELECT binding.media_id
                    FROM calendar_attachment_binding binding
                    JOIN stored_media media ON media.id = binding.media_id
                    WHERE binding.id = ? AND binding.plan_id = ?
                      AND binding.workspace_id = ?
                      AND binding.created_by_user_id = ?
                      AND binding.status = 'ACTIVE'
                      AND media.status = 'AVAILABLE'
                    FOR UPDATE OF binding
                    """,
                    (row, index) -> new Content(row.getLong("media_id")),
                    contentId,
                    share.planId(),
                    context.workspaceId(),
                    context.actorId());
            return exactlyOne(rows);
        }
        List<Content> rows = jdbc.query(
                """
                SELECT NULL::BIGINT AS media_id
                FROM calendar_knowledge_excerpt excerpt
                LEFT JOIN calendar_knowledge_fact_binding fact
                  ON fact.id = excerpt.fact_binding_id
                LEFT JOIN calendar_knowledge_annotation_binding annotation
                  ON annotation.id = excerpt.annotation_binding_id
                WHERE excerpt.id = ? AND excerpt.plan_id = ?
                  AND excerpt.workspace_id = ?
                  AND excerpt.created_by_user_id = ?
                  AND excerpt.status = 'APPROVED'
                  AND (
                    (excerpt.source_kind = 'FACT'
                      AND fact.status = 'ACTIVE'
                      AND fact.review_state = 'CURRENT')
                    OR
                    (excerpt.source_kind = 'ANNOTATION'
                      AND annotation.status = 'ACTIVE'
                      AND annotation.review_state = 'CURRENT'))
                FOR UPDATE OF excerpt
                """,
                (row, index) -> new Content(null),
                contentId,
                share.planId(),
                context.workspaceId(),
                context.actorId());
        return exactlyOne(rows);
    }

    private static Content exactlyOne(List<Content> rows) {
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar share content", "requested content");
        }
        return rows.getFirst();
    }

    private LockedGrant lockGrant(UUID id, WorkspaceContext context) {
        List<LockedGrant> rows = jdbc.query(
                """
                SELECT id, share_id, content_kind, status, grant_revision,
                       grantee_user_id, revoke_request_hash,
                       revoke_payload_hash
                FROM calendar_share_content_grant
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, index) -> lockedGrant(row),
                id,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar content grant", "requested grant");
        }
        return rows.getFirst();
    }

    private CalendarContentGrantView getOwned(
            UUID id, WorkspaceContext context) {
        List<CalendarContentGrantView> rows = jdbc.query(
                """
                SELECT id, share_id, content_kind, status, grant_revision
                FROM calendar_share_content_grant
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                CalendarShareContentGrantService::view,
                id,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar content grant", "requested grant");
        }
        return rows.getFirst();
    }

    private CalendarContentGrantView findByCreationRequest(
            String hash, WorkspaceContext context) {
        return first(
                """
                creation_request_hash = ?
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                hash,
                context.workspaceId(),
                context.actorId());
    }

    private CalendarContentGrantView findActive(
            UUID shareId,
            CalendarContentGrantView.ContentKind kind,
            UUID contentId,
            WorkspaceContext context) {
        String column = kind == CalendarContentGrantView.ContentKind.ATTACHMENT
                ? "attachment_binding_id"
                : "knowledge_excerpt_id";
        return first(
                """
                share_id = ? AND content_kind = ? AND %s = ?
                AND status = 'ACTIVE'
                AND workspace_id = ? AND created_by_user_id = ?
                """
                        .formatted(column),
                shareId,
                kind.name(),
                contentId,
                context.workspaceId(),
                context.actorId());
    }

    private CalendarContentGrantView first(String predicate, Object... args) {
        List<CalendarContentGrantView> rows = jdbc.query(
                """
                SELECT id, share_id, content_kind, status, grant_revision
                FROM calendar_share_content_grant WHERE %s LIMIT 2
                """
                        .formatted(predicate),
                CalendarShareContentGrantService::view,
                args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requirePayload(UUID id, String expected) {
        String actual = jdbc.queryForObject(
                """
                SELECT creation_payload_hash
                FROM calendar_share_content_grant WHERE id = ?
                """,
                String.class,
                id);
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for other calendar content");
        }
    }

    private static CalendarContentGrantView view(ResultSet row, int index)
            throws SQLException {
        return new CalendarContentGrantView(
                row.getObject("id", UUID.class),
                row.getObject("share_id", UUID.class),
                CalendarContentGrantView.ContentKind.valueOf(
                        row.getString("content_kind")),
                CalendarContentGrantView.Status.valueOf(row.getString("status")),
                row.getLong("grant_revision"));
    }

    private static CalendarContentGrantView lockedView(LockedGrant grant) {
        return new CalendarContentGrantView(
                grant.id(),
                grant.shareId(),
                CalendarContentGrantView.ContentKind.valueOf(grant.contentKind()),
                CalendarContentGrantView.Status.valueOf(grant.status()),
                grant.revision());
    }

    private static LockedGrant lockedGrant(ResultSet row) throws SQLException {
        return new LockedGrant(
                row.getObject("id", UUID.class),
                row.getObject("share_id", UUID.class),
                row.getString("content_kind"),
                row.getString("status"),
                row.getLong("grant_revision"),
                row.getObject("grantee_user_id", UUID.class),
                row.getString("revoke_request_hash"),
                row.getString("revoke_payload_hash"));
    }

    private record LockedShare(UUID id, UUID planId, UUID granteeUserId) {}

    private record Content(Long mediaId) {}

    private record LockedGrant(
            UUID id,
            UUID shareId,
            String contentKind,
            String status,
            long revision,
            UUID granteeUserId,
            String revokeRequestHash,
            String revokePayloadHash) {}

    private record AttachmentRow(
            String displayName,
            String mediaType,
            boolean image,
            String storageKey) {}
}
