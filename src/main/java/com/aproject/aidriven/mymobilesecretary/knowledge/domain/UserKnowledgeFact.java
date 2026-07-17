package com.aproject.aidriven.mymobilesecretary.knowledge.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** A bounded, actor-private fact explicitly taught by the user. */
@Entity
@Table(name = "user_knowledge_fact", uniqueConstraints = @UniqueConstraint(
        name = "uq_user_knowledge_fact_actor_subject",
        columnNames = {"workspace_id", "created_by_user_id", "category", "normalized_subject"}))
public class UserKnowledgeFact extends WorkspaceOwnedEntity {

    public enum Category {
        RELATIONSHIP,
        PLACE_GUIDANCE,
        INTERPRETATION_PREFERENCE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Category category;

    @Column(nullable = false, length = 160)
    private String subject;

    @Column(nullable = false, length = 160)
    private String normalizedSubject;

    @Column(nullable = false, length = 1200)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserKnowledgeFact() {
    }

    public static UserKnowledgeFact create(Category category, String subject,
                                           String normalizedSubject, String detail, Instant now) {
        UserKnowledgeFact fact = new UserKnowledgeFact();
        fact.category = category;
        fact.subject = subject;
        fact.normalizedSubject = normalizedSubject;
        fact.detail = detail;
        fact.createdAt = now;
        fact.updatedAt = now;
        return fact;
    }

    public void update(String subject, String detail, Instant now) {
        this.subject = subject;
        this.detail = detail;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public Category getCategory() { return category; }
    public String getSubject() { return subject; }
    public String getNormalizedSubject() { return normalizedSubject; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
