package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CalendarSourceMutationSignalService {

    public enum MutationKind {
        NODE_TIME,
        NODE_LOCATION,
        NODE_CANCELLATION
    }

    private final JdbcTemplate jdbc;

    public CalendarSourceMutationSignalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recordOwnerGeneral(
            CalendarTimeNodeEntity node,
            MutationKind mutationKind,
            Instant occurredAt) {
        WorkspaceContext context =
                WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar source mutation requires a tenant workspace");
        }
        jdbc.update(
                """
                INSERT INTO calendar_personal_projection_signal (
                    id, signal_kind, source_outbox_id,
                    authoritative_mutation_id, share_id, plan_id,
                    source_node_id, source_revision, recipient_user_id,
                    mutation_mode, mutation_kind, source_origin,
                    payload_text, created_at, workspace_id,
                    created_by_user_id)
                VALUES (?, 'SOURCE_MUTATION', NULL, NULL, NULL, ?, ?, ?,
                        NULL, 'GENERAL_REVIEW', ?, 'OWNER', ?, ?, ?, ?)
                ON CONFLICT (
                    workspace_id, created_by_user_id,
                    source_node_id, source_revision, mutation_mode)
                WHERE signal_kind = 'SOURCE_MUTATION'
                DO NOTHING
                """,
                UUID.randomUUID(),
                node.getPlanId(),
                node.getId(),
                node.getRevision(),
                mutationKind.name(),
                "Owner calendar node changed",
                Timestamp.from(occurredAt),
                context.workspaceId(),
                node.getCreatedByUserId());
    }
}
