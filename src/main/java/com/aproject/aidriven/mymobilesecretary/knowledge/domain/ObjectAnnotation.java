package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Actor-private free-form annotation attached to a domain object through generic tags. */
@Entity
public class ObjectAnnotation extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 240)
    private String subject;

    @Column(nullable = false, length = 1200)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant archivedAt;

    protected ObjectAnnotation() {
    }

    public static ObjectAnnotation create(TargetType targetType, Long targetId,
                                          String subject, String detail, Instant now) {
        if (targetType == null || targetId == null || targetId <= 0) {
            throw new IllegalArgumentException("annotation target is required");
        }
        ObjectAnnotation annotation = new ObjectAnnotation();
        annotation.targetType = targetType;
        annotation.targetId = targetId;
        annotation.subject = clean(subject, 240, "annotation subject is required");
        annotation.detail = clean(detail, 1200, "annotation detail is required");
        annotation.createdAt = java.util.Objects.requireNonNull(now);
        annotation.updatedAt = now;
        return annotation;
    }

    private static String clean(String value, int max, String error) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(error);
        String cleaned = value.strip().replace('\r', ' ').replace('\n', ' ');
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }

    public Long getId() { return id; }
    public TargetType getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public String getSubject() { return subject; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public boolean isArchived() { return archivedAt != null; }

    public void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = java.util.Objects.requireNonNull(now);
            updatedAt = now;
        }
    }

    public void update(String newSubject, String newDetail, Instant now) {
        if (isArchived()) throw new IllegalStateException("archived knowledge cannot be edited");
        subject = clean(newSubject, 240, "annotation subject is required");
        detail = clean(newDetail, 1200, "annotation detail is required");
        updatedAt = java.util.Objects.requireNonNull(now);
    }

    public enum TargetType {
        PRODUCT_OBSERVATION,
        PRICE_RECORD,
        MEDIA,
        ITEM,
        TASK,
        SCHEDULE
    }
}
