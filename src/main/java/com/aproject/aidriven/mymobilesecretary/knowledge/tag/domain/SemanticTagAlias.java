package com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class SemanticTagAlias extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long tagId;
    @Column(nullable = false, length = 120)
    private String aliasName;
    @Column(nullable = false, length = 120)
    private String normalizedAlias;
    @Column(nullable = false)
    private Instant createdAt;

    protected SemanticTagAlias() {}

    public static SemanticTagAlias create(Long tagId, String alias, Instant now) {
        if (tagId == null || alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("tag alias requires tag and name");
        }
        SemanticTagAlias value = new SemanticTagAlias();
        value.tagId = tagId;
        value.aliasName = alias.strip();
        value.normalizedAlias = SemanticTag.normalize(alias);
        value.createdAt = now;
        return value;
    }

    public Long getTagId() { return tagId; }
}
