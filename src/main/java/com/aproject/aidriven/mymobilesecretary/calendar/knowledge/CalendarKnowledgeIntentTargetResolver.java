package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a human Calendar reference without ever accepting an LLM-provided identifier.
 *
 * <p>Zero and multiple matches are deliberately indistinguishable to callers.
 */
@Service
@Transactional(readOnly = true)
public class CalendarKnowledgeIntentTargetResolver {

    private final JdbcTemplate jdbc;

    public CalendarKnowledgeIntentTargetResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CalendarKnowledgeTarget> resolve(
            String planTitle, String targetKind, String childLabel) {
        WorkspaceContext context = context();
        Optional<UUID> planId = uniquePlan(planTitle, context);
        if (planId.isEmpty()) {
            return Optional.empty();
        }
        CalendarKnowledgeTarget.TargetKind kind;
        try {
            kind = CalendarKnowledgeTarget.TargetKind.valueOf(
                    required(targetKind).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return switch (kind) {
            case PLAN -> Optional.of(CalendarKnowledgeTarget.plan(planId.get()));
            case ACTIVITY -> uniqueChild(
                            """
                            SELECT id
                            FROM calendar_activity
                            WHERE plan_id = ?
                              AND workspace_id = ?
                              AND created_by_user_id = ?
                              AND lower(btrim(title)) = lower(btrim(?))
                            ORDER BY created_at, id
                            LIMIT 2
                            """,
                            planId.get(),
                            context,
                            childLabel)
                    .map(id -> CalendarKnowledgeTarget.activity(planId.get(), id));
            case NODE -> uniqueChild(
                            """
                            SELECT id
                            FROM calendar_time_node
                            WHERE plan_id = ?
                              AND workspace_id = ?
                              AND created_by_user_id = ?
                              AND (lower(btrim(label)) = lower(btrim(?))
                                   OR lower(btrim(node_key)) = lower(btrim(?)))
                            ORDER BY created_at, id
                            LIMIT 2
                            """,
                            planId.get(),
                            context,
                            childLabel)
                    .map(id -> CalendarKnowledgeTarget.node(planId.get(), id));
        };
    }

    private Optional<UUID> uniquePlan(
            String planTitle, WorkspaceContext context) {
        String title = clean(planTitle);
        List<UUID> matches;
        if (title == null) {
            matches = jdbc.queryForList(
                    """
                    SELECT id
                    FROM calendar_plan
                    WHERE workspace_id = ?
                      AND created_by_user_id = ?
                      AND status = 'ACTIVE'
                    ORDER BY updated_at DESC, id
                    LIMIT 2
                    """,
                    UUID.class,
                    context.workspaceId(),
                    context.actorId());
        } else {
            matches = jdbc.queryForList(
                    """
                    SELECT id
                    FROM calendar_plan
                    WHERE workspace_id = ?
                      AND created_by_user_id = ?
                      AND status = 'ACTIVE'
                      AND lower(btrim(title)) = lower(btrim(?))
                    ORDER BY updated_at DESC, id
                    LIMIT 2
                    """,
                    UUID.class,
                    context.workspaceId(),
                    context.actorId(),
                    title);
        }
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private Optional<UUID> uniqueChild(
            String sql,
            UUID planId,
            WorkspaceContext context,
            String childLabel) {
        String label = clean(childLabel);
        if (label == null) {
            return Optional.empty();
        }
        Object[] arguments = sql.contains("node_key")
                ? new Object[] {
                    planId,
                    context.workspaceId(),
                    context.actorId(),
                    label,
                    label
                }
                : new Object[] {
                    planId,
                    context.workspaceId(),
                    context.actorId(),
                    label
                };
        List<UUID> matches = jdbc.queryForList(sql, UUID.class, arguments);
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar knowledge intent target requires tenant scope");
        }
        return context;
    }

    private static String required(String value) {
        String cleaned = clean(value);
        if (cleaned == null) {
            throw new IllegalArgumentException("target kind is required");
        }
        return cleaned;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.strip();
        return cleaned.length() <= 200 ? cleaned : null;
    }
}
