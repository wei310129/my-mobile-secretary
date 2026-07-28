package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import com.aproject.aidriven.mymobilesecretary.media.application.StoredMediaDeletedEvent;
import java.sql.Timestamp;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CalendarAttachmentLifecycleListener {

    private final JdbcTemplate jdbc;

    public CalendarAttachmentLifecycleListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    @Transactional
    public void onMediaDeleted(StoredMediaDeletedEvent event) {
        jdbc.update(
                """
                UPDATE calendar_attachment_binding
                SET status = 'MEDIA_DELETED',
                    binding_revision = binding_revision + 1,
                    updated_at = ?
                WHERE media_id = ? AND workspace_id = ?
                  AND created_by_user_id = ? AND status = 'ACTIVE'
                """,
                Timestamp.from(event.deletedAt()),
                event.mediaId(),
                event.workspaceId(),
                event.actorId());
    }
}
