package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
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
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarKnowledgeExcerptService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CalendarKnowledgeExcerptService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public CalendarKnowledgeExcerptView createFactDraft(
            String requestKey, UUID bindingId, String title, String text) {
        return createDraft(
                CalendarKnowledgeExcerptView.SourceKind.FACT,
                requestKey,
                bindingId,
                title,
                text);
    }

    public CalendarKnowledgeExcerptView createAnnotationDraft(
            String requestKey, UUID bindingId, String title, String text) {
        return createDraft(
                CalendarKnowledgeExcerptView.SourceKind.ANNOTATION,
                requestKey,
                bindingId,
                title,
                text);
    }

    public CalendarKnowledgeExcerptView approve(UUID id, long expectedRevision) {
        CalendarKnowledgeExcerptView excerpt = get(id);
        Binding source = binding(
                excerpt.sourceKind(), excerpt.bindingId(), context(), true);
        if (!"ACTIVE".equals(source.status())
                || !"CURRENT".equals(source.reviewState())) {
            throw new BusinessException(
                    "KNOWLEDGE_BINDING_REVIEW_REQUIRED",
                    "Knowledge binding must be current before approving an excerpt");
        }
        return transition(id, expectedRevision, "DRAFT", "APPROVED");
    }

    public CalendarKnowledgeExcerptView revoke(UUID id, long expectedRevision) {
        WorkspaceContext context = context();
        Instant now = Instant.now(clock);
        int changed = jdbc.update(
                """
                UPDATE calendar_knowledge_excerpt
                SET status = 'REVOKED', revoked_at = ?,
                    row_revision = row_revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status IN ('DRAFT', 'APPROVED', 'REVIEW_REQUIRED')
                  AND row_revision = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                id,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        requireChanged(changed);
        return get(id);
    }

    @Transactional(readOnly = true)
    public CalendarKnowledgeExcerptView get(UUID id) {
        WorkspaceContext context = context();
        List<CalendarKnowledgeExcerptView> rows = jdbc.query(
                """
                SELECT excerpt.id, excerpt.source_kind,
                       COALESCE(
                           excerpt.fact_binding_id,
                           excerpt.annotation_binding_id) AS binding_id,
                       excerpt.plan_id, excerpt.version_number,
                       excerpt.snapshot_title, excerpt.snapshot_text,
                       excerpt.status, excerpt.row_revision
                FROM calendar_knowledge_excerpt excerpt
                WHERE excerpt.id = ?
                  AND excerpt.workspace_id = ?
                  AND excerpt.created_by_user_id = ?
                """,
                CalendarKnowledgeExcerptService::view,
                id,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar knowledge excerpt", "requested excerpt");
        }
        return rows.getFirst();
    }

    @Transactional(readOnly = true)
    public List<CalendarKnowledgeExcerptView> listGrantEligible(UUID planId) {
        WorkspaceContext context = context();
        return jdbc.query(
                """
                SELECT excerpt.id, excerpt.source_kind,
                       COALESCE(
                           excerpt.fact_binding_id,
                           excerpt.annotation_binding_id) AS binding_id,
                       excerpt.plan_id, excerpt.version_number,
                       excerpt.snapshot_title, excerpt.snapshot_text,
                       excerpt.status, excerpt.row_revision
                FROM calendar_knowledge_excerpt excerpt
                LEFT JOIN calendar_knowledge_fact_binding fact
                  ON fact.id = excerpt.fact_binding_id
                 AND fact.workspace_id = excerpt.workspace_id
                 AND fact.created_by_user_id = excerpt.created_by_user_id
                LEFT JOIN calendar_knowledge_annotation_binding annotation
                  ON annotation.id = excerpt.annotation_binding_id
                 AND annotation.workspace_id = excerpt.workspace_id
                 AND annotation.created_by_user_id = excerpt.created_by_user_id
                WHERE excerpt.plan_id = ? AND excerpt.status = 'APPROVED'
                  AND excerpt.workspace_id = ?
                  AND excerpt.created_by_user_id = ?
                  AND (
                      (excerpt.source_kind = 'FACT'
                       AND fact.status = 'ACTIVE'
                       AND fact.review_state = 'CURRENT')
                      OR
                      (excerpt.source_kind = 'ANNOTATION'
                       AND annotation.status = 'ACTIVE'
                       AND annotation.review_state = 'CURRENT'))
                ORDER BY excerpt.version_number, excerpt.id
                LIMIT 50
                """,
                CalendarKnowledgeExcerptService::view,
                planId,
                context.workspaceId(),
                context.actorId());
    }

    private CalendarKnowledgeExcerptView createDraft(
            CalendarKnowledgeExcerptView.SourceKind kind,
            String requestKey,
            UUID bindingId,
            String title,
            String text) {
        WorkspaceContext context = context();
        String requestHash = hash(requireBounded(requestKey, 160, "request key"));
        String safeTitle = requireBounded(title, 240, "snapshot title");
        String safeText = requireBounded(text, 2000, "snapshot text");
        String payloadHash = hash(kind + "|" + bindingId + "|" + safeTitle + "|" + safeText);
        CalendarKnowledgeExcerptView replay = findByRequest(requestHash, context);
        if (replay != null) {
            requirePayload(replay.id(), payloadHash);
            return replay;
        }
        Binding binding = binding(kind, bindingId, context, true);
        if (!"ACTIVE".equals(binding.status())
                || !"CURRENT".equals(binding.reviewState())) {
            throw new BusinessException(
                    "KNOWLEDGE_BINDING_REVIEW_REQUIRED",
                    "Knowledge binding must be current before creating an excerpt");
        }
        Integer nextVersion = jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM calendar_knowledge_excerpt
                WHERE %s = ? AND workspace_id = ? AND created_by_user_id = ?
                """
                        .formatted(bindingColumn(kind)),
                Integer.class,
                bindingId,
                context.workspaceId(),
                context.actorId());
        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_knowledge_excerpt (
                    id, source_kind, fact_binding_id, annotation_binding_id,
                    plan_id, version_number, snapshot_title, snapshot_text,
                    status, row_revision, creation_request_hash,
                    creation_payload_hash, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?, ?, ?, ?, ?)
                """,
                id,
                kind.name(),
                kind == CalendarKnowledgeExcerptView.SourceKind.FACT ? bindingId : null,
                kind == CalendarKnowledgeExcerptView.SourceKind.ANNOTATION
                        ? bindingId
                        : null,
                binding.planId(),
                nextVersion,
                safeTitle,
                safeText,
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        return get(id);
    }

    private CalendarKnowledgeExcerptView transition(
            UUID id, long expectedRevision, String from, String to) {
        WorkspaceContext context = context();
        Instant now = Instant.now(clock);
        int changed = jdbc.update(
                """
                UPDATE calendar_knowledge_excerpt
                SET status = ?, approved_at = ?,
                    row_revision = row_revision + 1, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = ? AND row_revision = ?
                """,
                to,
                Timestamp.from(now),
                Timestamp.from(now),
                id,
                context.workspaceId(),
                context.actorId(),
                from,
                expectedRevision);
        requireChanged(changed);
        return get(id);
    }

    private Binding binding(
            CalendarKnowledgeExcerptView.SourceKind kind,
            UUID bindingId,
            WorkspaceContext context,
            boolean lock) {
        String table = kind == CalendarKnowledgeExcerptView.SourceKind.FACT
                ? "calendar_knowledge_fact_binding"
                : "calendar_knowledge_annotation_binding";
        List<Binding> rows = jdbc.query(
                """
                SELECT plan_id, status, review_state
                FROM %s
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                %s
                """
                        .formatted(table, lock ? "FOR UPDATE" : ""),
                (row, index) -> new Binding(
                        row.getObject("plan_id", UUID.class),
                        row.getString("status"),
                        row.getString("review_state")),
                bindingId,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException("Calendar knowledge binding", "requested binding");
        }
        return rows.getFirst();
    }

    private CalendarKnowledgeExcerptView findByRequest(
            String requestHash, WorkspaceContext context) {
        List<CalendarKnowledgeExcerptView> rows = jdbc.query(
                """
                SELECT id, source_kind,
                       COALESCE(fact_binding_id, annotation_binding_id) AS binding_id,
                       plan_id, version_number, snapshot_title, snapshot_text,
                       status, row_revision
                FROM calendar_knowledge_excerpt
                WHERE creation_request_hash = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                CalendarKnowledgeExcerptService::view,
                requestHash,
                context.workspaceId(),
                context.actorId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requirePayload(UUID id, String expected) {
        String actual = jdbc.queryForObject(
                """
                SELECT creation_payload_hash
                FROM calendar_knowledge_excerpt WHERE id = ?
                """,
                String.class,
                id);
        if (!expected.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for another excerpt");
        }
    }

    private static CalendarKnowledgeExcerptView view(
            ResultSet row, int index) throws SQLException {
        return new CalendarKnowledgeExcerptView(
                row.getObject("id", UUID.class),
                CalendarKnowledgeExcerptView.SourceKind.valueOf(
                        row.getString("source_kind")),
                row.getObject("binding_id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getInt("version_number"),
                row.getString("snapshot_title"),
                row.getString("snapshot_text"),
                CalendarKnowledgeExcerptView.Status.valueOf(row.getString("status")),
                row.getLong("row_revision"));
    }

    private static void requireChanged(int changed) {
        if (changed != 1) {
            throw new BusinessException(
                    "KNOWLEDGE_EXCERPT_REVISION_CONFLICT",
                    "Knowledge excerpt changed; reload before continuing");
        }
    }

    private static String bindingColumn(
            CalendarKnowledgeExcerptView.SourceKind kind) {
        return kind == CalendarKnowledgeExcerptView.SourceKind.FACT
                ? "fact_binding_id"
                : "annotation_binding_id";
    }

    private static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Knowledge excerpt requires tenant scope");
        }
        return context;
    }

    private static String requireBounded(String value, int max, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String safe = value.strip();
        if (safe.length() > max) {
            throw new IllegalArgumentException(name + " exceeds " + max);
        }
        return safe;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Binding(UUID planId, String status, String reviewState) {}
}
