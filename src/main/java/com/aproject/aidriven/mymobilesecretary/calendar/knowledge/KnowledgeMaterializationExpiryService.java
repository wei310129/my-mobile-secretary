package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeMaterializationExpiryService {

    private final JdbcTemplate jdbc;

    public KnowledgeMaterializationExpiryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireIfDue(
            UUID proposalId, UUID workspaceId, UUID actorId, Instant now) {
        jdbc.update(
                """
                UPDATE knowledge_materialization
                SET status = 'EXPIRED', row_revision = row_revision + 1,
                    resolution_kind = 'EXPIRE', updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'PENDING_CONFIRMATION' AND expires_at <= ?
                """,
                Timestamp.from(now),
                proposalId,
                workspaceId,
                actorId,
                Timestamp.from(now));
    }
}
