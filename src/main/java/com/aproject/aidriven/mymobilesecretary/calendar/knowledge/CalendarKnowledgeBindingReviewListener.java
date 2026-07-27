package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationArchivedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ObjectAnnotationUpdatedEvent;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeFactUpdatedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CalendarKnowledgeBindingReviewListener {

    private final JdbcTemplate jdbc;

    public CalendarKnowledgeBindingReviewListener(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    @Transactional
    public void onFactUpdated(UserKnowledgeFactUpdatedEvent event) {
        markForReview(
                "calendar_knowledge_fact_binding",
                "fact_id",
                event.factId(),
                event.updatedAt());
        markExcerptsForReview(
                "fact_binding_id",
                "calendar_knowledge_fact_binding",
                "fact_id",
                event.factId(),
                event.updatedAt());
    }

    @EventListener
    @Transactional
    public void onAnnotationUpdated(ObjectAnnotationUpdatedEvent event) {
        markForReview(
                "calendar_knowledge_annotation_binding",
                "annotation_id",
                event.annotationId(),
                event.updatedAt());
        markExcerptsForReview(
                "annotation_binding_id",
                "calendar_knowledge_annotation_binding",
                "annotation_id",
                event.annotationId(),
                event.updatedAt());
    }

    @EventListener
    @Transactional
    public void onAnnotationArchived(ObjectAnnotationArchivedEvent event) {
        jdbc.update(
                """
                UPDATE calendar_knowledge_annotation_binding
                SET status = 'ARCHIVED',
                    binding_revision = binding_revision + 1,
                    source_updated_at = ?,
                    updated_at = ?
                WHERE annotation_id = ?
                  AND status = 'ACTIVE'
                """,
                Timestamp.from(event.archivedAt()),
                Timestamp.from(event.archivedAt()),
                event.annotationId());
        markExcerptsForReview(
                "annotation_binding_id",
                "calendar_knowledge_annotation_binding",
                "annotation_id",
                event.annotationId(),
                event.archivedAt());
    }

    private void markForReview(
            String table, String sourceColumn, long sourceId, Instant updatedAt) {
        jdbc.update(
                """
                UPDATE %s
                SET review_state = 'REVIEW_REQUIRED',
                    binding_revision = binding_revision + 1,
                    source_updated_at = ?,
                    updated_at = ?
                WHERE %s = ?
                  AND status = 'ACTIVE'
                  AND review_state = 'CURRENT'
                """
                        .formatted(table, sourceColumn),
                Timestamp.from(updatedAt),
                Timestamp.from(updatedAt),
                sourceId);
    }

    private void markExcerptsForReview(
            String excerptBindingColumn,
            String bindingTable,
            String sourceColumn,
            long sourceId,
            Instant updatedAt) {
        jdbc.update(
                """
                UPDATE calendar_knowledge_excerpt
                SET status = 'REVIEW_REQUIRED',
                    row_revision = row_revision + 1,
                    updated_at = ?
                WHERE status = 'APPROVED'
                  AND %s IN (
                      SELECT id FROM %s WHERE %s = ?)
                """
                        .formatted(
                                excerptBindingColumn, bindingTable, sourceColumn),
                Timestamp.from(updatedAt),
                sourceId);
    }
}
