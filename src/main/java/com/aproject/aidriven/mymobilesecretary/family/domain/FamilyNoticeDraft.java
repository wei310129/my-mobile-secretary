package com.aproject.aidriven.mymobilesecretary.family.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A normalized teacher/family notice awaiting explicit actor confirmation. */
@Entity
@Table(name = "family_notice_draft")
public class FamilyNoticeDraft extends WorkspaceOwnedEntity {

    public enum Status { PENDING, CONFIRMED, DISCARDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 30000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected FamilyNoticeDraft() {
    }

    public static FamilyNoticeDraft create(String title, String payload,
                                           Instant expiresAt, Instant now) {
        FamilyNoticeDraft draft = new FamilyNoticeDraft();
        draft.title = title;
        draft.payload = payload;
        draft.status = Status.PENDING;
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void replacePayload(String payload, Instant now) {
        requirePending(now);
        this.payload = payload;
        this.updatedAt = now;
    }

    public void replaceTitleAndPayload(String title, String payload, Instant now) {
        requirePending(now);
        if (title == null || title.isBlank() || title.length() > 160) {
            throw new IllegalArgumentException("family notice title is invalid");
        }
        this.title = title.strip();
        this.payload = payload;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        requirePending(now);
        status = Status.CONFIRMED;
        updatedAt = now;
    }

    public void discard(Instant now) {
        requirePending(now);
        status = Status.DISCARDED;
        updatedAt = now;
    }

    private void requirePending(Instant now) {
        if (status != Status.PENDING) {
            throw new IllegalStateException("family notice draft is not pending");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalStateException("family notice draft expired");
        }
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
