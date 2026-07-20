package com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Locale;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class SemanticTag extends WorkspaceOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String canonicalName;
    @Column(nullable = false, length = 120)
    private String normalizedName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private Kind kind;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected SemanticTag() {}

    public static SemanticTag create(String name, Kind kind, Instant now) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tag name is required");
        SemanticTag tag = new SemanticTag();
        tag.canonicalName = name.strip();
        tag.normalizedName = normalize(name);
        tag.kind = kind == null ? Kind.OTHER : kind;
        tag.createdAt = now;
        tag.updatedAt = now;
        return tag;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    public Long getId() { return id; }
    public String getCanonicalName() { return canonicalName; }
    public String getNormalizedName() { return normalizedName; }
    public Kind getKind() { return kind; }

    public enum Kind { ORGANIZATION, CATEGORY, PRODUCT, SERVICE, BENEFIT, ACTIVITY, TOPIC, OTHER }
}
