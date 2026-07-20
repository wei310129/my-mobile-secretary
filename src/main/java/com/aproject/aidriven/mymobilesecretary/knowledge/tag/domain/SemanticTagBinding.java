package com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class SemanticTagBinding extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long tagId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private TargetType targetType;
    @Column(nullable = false)
    private Long targetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private SemanticTagEdge.SourceType sourceType;
    @Column(nullable = false)
    private Instant createdAt;

    protected SemanticTagBinding() {}

    public static SemanticTagBinding create(Long tagId, TargetType targetType, Long targetId,
                                            SemanticTagEdge.SourceType source, Instant now) {
        SemanticTagBinding binding = new SemanticTagBinding();
        binding.tagId = java.util.Objects.requireNonNull(tagId);
        binding.targetType = java.util.Objects.requireNonNull(targetType);
        binding.targetId = java.util.Objects.requireNonNull(targetId);
        binding.sourceType = java.util.Objects.requireNonNull(source);
        binding.createdAt = now;
        return binding;
    }

    public Long getTagId() { return tagId; }
    public TargetType getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }

    public enum TargetType {
        PRICE_RECORD, LIFE_RECORD, SCHEDULE_ITEM, TASK, REMINDER, EVENT, MEDIA, EXTERNAL_CONTACT,
        SCHOOL_MEAL, BLOOD_DONATION, OBJECT_ANNOTATION
    }
}
