package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.KnowledgeTextNormalizer;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CalendarKnowledgeReadService {

    public static final int MAX_QUERY_LENGTH = 160;
    public static final int MAX_RESULTS = 10;

    private final JdbcTemplate jdbc;
    private final CalendarPlanRepository plans;
    private final CalendarActivityRepository activities;
    private final CalendarTimeNodeRepository nodes;

    public CalendarKnowledgeReadService(
            JdbcTemplate jdbc,
            CalendarPlanRepository plans,
            CalendarActivityRepository activities,
            CalendarTimeNodeRepository nodes) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.activities = activities;
        this.nodes = nodes;
    }

    public List<CalendarKnowledgeEvidenceView> retrieve(
            CalendarKnowledgeTarget target, String queryText, int requestedLimit) {
        WorkspaceContext context = context();
        authorizeTarget(target, context);
        String safeQuery = queryText == null ? "" : queryText.strip();
        if (safeQuery.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Calendar knowledge query is too long");
        }
        String normalized = KnowledgeTextNormalizer.normalize(safeQuery);
        if (normalized.isBlank()) {
            return List.of();
        }
        int limit = Math.min(
                requestedLimit <= 0 ? MAX_RESULTS : requestedLimit,
                MAX_RESULTS);
        String pattern = escapeLikePrefix(normalized) + "%";
        TargetSql targetSql = targetSql(target);
        List<EvidenceRow> evidence = new ArrayList<>();
        evidence.addAll(queryFacts(target, targetSql, pattern, normalized, limit, context));
        evidence.addAll(
                queryAnnotations(target, targetSql, pattern, normalized, limit, context));
        return evidence.stream()
                .sorted(Comparator
                        .comparing((EvidenceRow row) ->
                                !KnowledgeTextNormalizer.normalize(row.view().title())
                                        .equals(normalized))
                        .thenComparing(
                                row -> row.view().updatedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(row -> row.view().sourceKind().name())
                        .thenComparing(row -> row.view().title())
                        .thenComparing(EvidenceRow::sourceId, Comparator.reverseOrder()))
                .limit(limit)
                .map(EvidenceRow::view)
                .toList();
    }

    private List<EvidenceRow> queryFacts(
            CalendarKnowledgeTarget target,
            TargetSql targetSql,
            String pattern,
            String normalized,
            int limit,
            WorkspaceContext context) {
        return jdbc.query(
                """
                SELECT fact.id AS source_id,
                       fact.subject AS title, fact.detail AS content,
                       fact.updated_at
                FROM calendar_knowledge_fact_binding binding
                JOIN user_knowledge_fact fact
                  ON fact.id = binding.fact_id
                 AND fact.workspace_id = binding.workspace_id
                 AND fact.created_by_user_id = binding.created_by_user_id
                WHERE binding.plan_id = ?
                  AND binding.target_kind = ?
                  AND %s
                  AND binding.status = 'ACTIVE'
                  AND binding.review_state = 'CURRENT'
                  AND binding.workspace_id = ?
                  AND binding.created_by_user_id = ?
                  AND fact.normalized_subject LIKE ? ESCAPE '!'
                ORDER BY
                  CASE WHEN fact.normalized_subject = ? THEN 0 ELSE 1 END,
                  fact.updated_at DESC, fact.subject ASC, fact.id DESC
                LIMIT ?
                """
                        .formatted(targetSql.predicate()),
                (row, index) -> view(
                        row, CalendarKnowledgeEvidenceView.SourceKind.FACT),
                args(target, targetSql, context, pattern, normalized, limit));
    }

    private List<EvidenceRow> queryAnnotations(
            CalendarKnowledgeTarget target,
            TargetSql targetSql,
            String pattern,
            String normalized,
            int limit,
            WorkspaceContext context) {
        return jdbc.query(
                """
                SELECT annotation.id AS source_id,
                       annotation.subject AS title,
                       annotation.detail AS content,
                       annotation.updated_at
                FROM calendar_knowledge_annotation_binding binding
                JOIN object_annotation annotation
                  ON annotation.id = binding.annotation_id
                 AND annotation.workspace_id = binding.workspace_id
                 AND annotation.created_by_user_id =
                     binding.created_by_user_id
                WHERE binding.plan_id = ?
                  AND binding.target_kind = ?
                  AND %s
                  AND binding.status = 'ACTIVE'
                  AND binding.review_state = 'CURRENT'
                  AND binding.workspace_id = ?
                  AND binding.created_by_user_id = ?
                  AND annotation.archived_at IS NULL
                  AND annotation.normalized_subject LIKE ? ESCAPE '!'
                ORDER BY
                  CASE WHEN annotation.normalized_subject = ?
                       THEN 0 ELSE 1 END,
                   annotation.updated_at DESC,
                   annotation.subject ASC, annotation.id DESC
                LIMIT ?
                """
                        .formatted(targetSql.predicate()),
                (row, index) -> view(
                        row, CalendarKnowledgeEvidenceView.SourceKind.ANNOTATION),
                args(target, targetSql, context, pattern, normalized, limit));
    }

    private static Object[] args(
            CalendarKnowledgeTarget target,
            TargetSql targetSql,
            WorkspaceContext context,
            String pattern,
            String normalized,
            int limit) {
        List<Object> args = new ArrayList<>();
        args.add(target.planId());
        args.add(target.kind().name());
        if (targetSql.targetId() != null) {
            args.add(targetSql.targetId());
        }
        args.add(context.workspaceId());
        args.add(context.actorId());
        args.add(pattern);
        args.add(normalized);
        args.add(limit);
        return args.toArray();
    }

    private void authorizeTarget(
            CalendarKnowledgeTarget target, WorkspaceContext context) {
        var plan = plans.findByIdAndWorkspaceIdAndCreatedByUserId(
                        target.planId(), context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        if (plan.getStatus() != CalendarPlanStatus.ACTIVE) {
            throw new BusinessException(
                    "CALENDAR_PLAN_NOT_ACTIVE",
                    "Calendar knowledge is unavailable for an inactive plan");
        }
        if (target.kind() == CalendarKnowledgeTarget.TargetKind.ACTIVITY) {
            var activity = activities.findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.activityId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar activity", "requested activity"));
            if (!activity.getPlanId().equals(target.planId())) {
                throw new NotFoundException(
                        "Calendar activity", "requested activity");
            }
        } else if (target.kind() == CalendarKnowledgeTarget.TargetKind.NODE) {
            var node = nodes.findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.nodeId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar node", "requested node"));
            if (!node.getPlanId().equals(target.planId())) {
                throw new NotFoundException("Calendar node", "requested node");
            }
        }
    }

    private static TargetSql targetSql(CalendarKnowledgeTarget target) {
        return switch (target.kind()) {
            case PLAN -> new TargetSql(
                    "binding.activity_id IS NULL AND binding.node_id IS NULL",
                    null);
            case ACTIVITY -> new TargetSql(
                    "binding.activity_id = ? AND binding.node_id IS NULL",
                    target.activityId());
            case NODE -> new TargetSql(
                    "binding.activity_id IS NULL AND binding.node_id = ?",
                    target.nodeId());
        };
    }

    private static EvidenceRow view(
            ResultSet row, CalendarKnowledgeEvidenceView.SourceKind kind)
            throws SQLException {
        var timestamp = row.getTimestamp("updated_at");
        return new EvidenceRow(
                new CalendarKnowledgeEvidenceView(
                        kind,
                        row.getString("title"),
                        row.getString("content"),
                        timestamp == null ? null : timestamp.toInstant()),
                row.getLong("source_id"));
    }

    private static String escapeLikePrefix(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar knowledge retrieval requires tenant scope");
        }
        return context;
    }

    private record TargetSql(String predicate, UUID targetId) {}

    private record EvidenceRow(CalendarKnowledgeEvidenceView view, long sourceId) {}
}
