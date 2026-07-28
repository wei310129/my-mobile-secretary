package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.UserKnowledgeFactRepository;
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
public class CalendarKnowledgeBindingService {

    private final JdbcTemplate jdbc;
    private final UserKnowledgeFactRepository facts;
    private final ObjectAnnotationRepository annotations;
    private final CalendarPlanRepository plans;
    private final CalendarActivityRepository activities;
    private final CalendarTimeNodeRepository nodes;
    private final Clock clock;

    public CalendarKnowledgeBindingService(
            JdbcTemplate jdbc,
            UserKnowledgeFactRepository facts,
            ObjectAnnotationRepository annotations,
            CalendarPlanRepository plans,
            CalendarActivityRepository activities,
            CalendarTimeNodeRepository nodes,
            Clock clock) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.annotations = annotations;
        this.plans = plans;
        this.activities = activities;
        this.nodes = nodes;
        this.clock = clock;
    }

    public CalendarKnowledgeBindingView bindFact(
            String requestKey, long factId, CalendarKnowledgeTarget target) {
        WorkspaceContext context = tenantContext();
        var fact = facts.findByIdAndWorkspaceIdAndCreatedByUserId(
                        factId, context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException(
                        "Knowledge fact", "requested fact"));
        return bind(
                CalendarKnowledgeBindingView.SourceKind.FACT,
                factId,
                fact.getUpdatedAt(),
                requestKey,
                target,
                context);
    }

    public CalendarKnowledgeBindingView bindAnnotation(
            String requestKey, long annotationId, CalendarKnowledgeTarget target) {
        WorkspaceContext context = tenantContext();
        var annotation = annotations.findByIdAndWorkspaceIdAndCreatedByUserId(
                        annotationId, context.workspaceId(), context.actorId())
                .filter(candidate -> !candidate.isArchived())
                .orElseThrow(() -> new NotFoundException(
                        "Knowledge annotation", "requested annotation"));
        return bind(
                CalendarKnowledgeBindingView.SourceKind.ANNOTATION,
                annotationId,
                annotation.getUpdatedAt(),
                requestKey,
                target,
                context);
    }

    @Transactional(readOnly = true)
    public CalendarKnowledgeBindingView getFactBinding(long factId, UUID planId) {
        return getBinding(
                CalendarKnowledgeBindingView.SourceKind.FACT, factId, planId);
    }

    @Transactional(readOnly = true)
    public CalendarKnowledgeBindingView getFactBinding(
            long factId, CalendarKnowledgeTarget target) {
        return getBinding(
                CalendarKnowledgeBindingView.SourceKind.FACT, factId, target);
    }

    @Transactional(readOnly = true)
    public CalendarKnowledgeBindingView getAnnotationBinding(
            long annotationId, UUID planId) {
        return getBinding(
                CalendarKnowledgeBindingView.SourceKind.ANNOTATION,
                annotationId,
                planId);
    }

    @Transactional(readOnly = true)
    public CalendarKnowledgeBindingView getAnnotationBinding(
            long annotationId, CalendarKnowledgeTarget target) {
        return getBinding(
                CalendarKnowledgeBindingView.SourceKind.ANNOTATION,
                annotationId,
                target);
    }

    private CalendarKnowledgeBindingView getBinding(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            long sourceId,
            UUID planId) {
        WorkspaceContext context = tenantContext();
        List<CalendarKnowledgeBindingView> matches = query(
                sourceKind,
                """
                %s = ? AND plan_id = ?
                AND workspace_id = ? AND created_by_user_id = ?
                """
                        .formatted(sourceColumn(sourceKind)),
                sourceId,
                planId,
                context.workspaceId(),
                context.actorId());
        if (matches.size() != 1) {
            throw new NotFoundException(
                    "Calendar knowledge binding", "requested knowledge");
        }
        return matches.getFirst();
    }

    private CalendarKnowledgeBindingView getBinding(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            long sourceId,
            CalendarKnowledgeTarget target) {
        CalendarKnowledgeBindingView match =
                findBySourceAndTarget(sourceKind, sourceId, target, tenantContext());
        if (match == null) {
            throw new NotFoundException(
                    "Calendar knowledge binding", "requested knowledge target");
        }
        return match;
    }

    private CalendarKnowledgeBindingView bind(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            long sourceId,
            Instant sourceUpdatedAt,
            String requestKey,
            CalendarKnowledgeTarget target,
            WorkspaceContext context) {
        authorizeTarget(target, context);
        String requestHash = hash(requireRequestKey(requestKey));
        String payloadHash = hash(sourceKind + "|" + sourceId + "|" + target);
        CalendarKnowledgeBindingView replay =
                findByRequestHash(sourceKind, requestHash, context);
        if (replay != null) {
            requireSamePayload(sourceKind, replay.id(), payloadHash);
            return replay;
        }
        CalendarKnowledgeBindingView existing =
                findBySourceAndTarget(sourceKind, sourceId, target, context);
        if (existing != null) {
            return existing;
        }

        Instant now = Instant.now(clock);
        String table = table(sourceKind);
        String sourceColumn = sourceColumn(sourceKind);
        jdbc.update(
                """
                INSERT INTO %s (
                    id, %s, target_kind, plan_id, activity_id, node_id,
                    source_updated_at, review_state, status, binding_revision,
                    creation_request_hash, creation_payload_hash,
                    created_at, updated_at, workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'CURRENT', 'ACTIVE', 1, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """
                        .formatted(table, sourceColumn),
                UUID.randomUUID(),
                sourceId,
                target.kind().name(),
                target.planId(),
                target.activityId(),
                target.nodeId(),
                Timestamp.from(sourceUpdatedAt),
                requestHash,
                payloadHash,
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());

        CalendarKnowledgeBindingView result =
                findByRequestHash(sourceKind, requestHash, context);
        if (result == null) {
            result = findBySourceAndTarget(sourceKind, sourceId, target, context);
        }
        if (result == null) {
            throw new IllegalStateException(
                    "Calendar knowledge binding arbitration produced no row");
        }
        if (findByRequestHash(sourceKind, requestHash, context) != null) {
            requireSamePayload(sourceKind, result.id(), payloadHash);
        }
        return result;
    }

    private void authorizeTarget(
            CalendarKnowledgeTarget target, WorkspaceContext context) {
        var plan = plans.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        target.planId(), context.workspaceId(), context.actorId())
                .orElseThrow(() -> new NotFoundException(
                        "Calendar plan", "requested plan"));
        plan.requireActiveForMutation();
        if (target.kind() == CalendarKnowledgeTarget.TargetKind.ACTIVITY) {
            var activity = activities.findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.activityId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar activity", "requested activity"));
            if (!activity.getPlanId().equals(target.planId())) {
                throw new IllegalArgumentException(
                        "Calendar activity belongs to another plan");
            }
        }
        if (target.kind() == CalendarKnowledgeTarget.TargetKind.NODE) {
            var node = nodes.findByIdAndWorkspaceIdAndCreatedByUserId(
                            target.nodeId(), context.workspaceId(), context.actorId())
                    .orElseThrow(() -> new NotFoundException(
                            "Calendar node", "requested node"));
            if (!node.getPlanId().equals(target.planId())) {
                throw new IllegalArgumentException(
                        "Calendar node belongs to another plan");
            }
        }
    }

    private CalendarKnowledgeBindingView findByRequestHash(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            String requestHash,
            WorkspaceContext context) {
        List<CalendarKnowledgeBindingView> matches = query(
                sourceKind,
                """
                creation_request_hash = ?
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                requestHash,
                context.workspaceId(),
                context.actorId());
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private CalendarKnowledgeBindingView findBySourceAndTarget(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            long sourceId,
            CalendarKnowledgeTarget target,
            WorkspaceContext context) {
        List<CalendarKnowledgeBindingView> matches = query(
                sourceKind,
                """
                %s = ? AND target_kind = ? AND plan_id = ?
                AND activity_id IS NOT DISTINCT FROM ?
                AND node_id IS NOT DISTINCT FROM ?
                AND workspace_id = ? AND created_by_user_id = ?
                """
                        .formatted(sourceColumn(sourceKind)),
                sourceId,
                target.kind().name(),
                target.planId(),
                target.activityId(),
                target.nodeId(),
                context.workspaceId(),
                context.actorId());
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private List<CalendarKnowledgeBindingView> query(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            String predicate,
            Object... arguments) {
        return jdbc.query(
                """
                SELECT id, %s AS source_id, target_kind, plan_id,
                       source_updated_at, review_state, status, binding_revision
                FROM %s
                WHERE %s
                ORDER BY created_at, id
                LIMIT 2
                """
                        .formatted(sourceColumn(sourceKind), table(sourceKind), predicate),
                (result, row) -> view(sourceKind, result),
                arguments);
    }

    private void requireSamePayload(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            UUID id,
            String payloadHash) {
        String actual = jdbc.queryForObject(
                "SELECT creation_payload_hash FROM %s WHERE id = ?"
                        .formatted(table(sourceKind)),
                String.class,
                id);
        if (!payloadHash.equals(actual)) {
            throw new BusinessException(
                    "IDEMPOTENCY_CONFLICT",
                    "The request key was already used for a different knowledge binding");
        }
    }

    private static CalendarKnowledgeBindingView view(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            ResultSet result) throws SQLException {
        return new CalendarKnowledgeBindingView(
                result.getObject("id", UUID.class),
                sourceKind,
                result.getLong("source_id"),
                CalendarKnowledgeTarget.TargetKind.valueOf(
                        result.getString("target_kind")),
                result.getObject("plan_id", UUID.class),
                result.getTimestamp("source_updated_at").toInstant(),
                CalendarKnowledgeBindingView.ReviewState.valueOf(
                        result.getString("review_state")),
                CalendarKnowledgeBindingView.Status.valueOf(result.getString("status")),
                result.getLong("binding_revision"));
    }

    private static String table(CalendarKnowledgeBindingView.SourceKind kind) {
        return kind == CalendarKnowledgeBindingView.SourceKind.FACT
                ? "calendar_knowledge_fact_binding"
                : "calendar_knowledge_annotation_binding";
    }

    private static String sourceColumn(
            CalendarKnowledgeBindingView.SourceKind kind) {
        return kind == CalendarKnowledgeBindingView.SourceKind.FACT
                ? "fact_id"
                : "annotation_id";
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar knowledge binding requires a tenant workspace");
        }
        return context;
    }

    private static String requireRequestKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException(
                    "A bounded idempotency key is required");
        }
        return value.strip();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
