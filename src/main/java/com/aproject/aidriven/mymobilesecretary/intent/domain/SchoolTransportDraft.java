package com.aproject.aidriven.mymobilesecretary.intent.domain;

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

/** 逐輪補齊孩子固定上課與本人接送資訊的私人草稿。 */
@Entity
@Table(name = "school_transport_draft")
public class SchoolTransportDraft extends WorkspaceOwnedEntity {

    public enum Status { PENDING, COMPLETED, DISCARDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 8000)
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

    protected SchoolTransportDraft() {
    }

    public static SchoolTransportDraft create(String title, String payload,
                                               Instant expiresAt, Instant now) {
        SchoolTransportDraft draft = new SchoolTransportDraft();
        draft.title = title;
        draft.payload = payload;
        draft.status = Status.PENDING;
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void replace(String title, String payload, Instant now) {
        requirePending(now);
        this.title = title;
        this.payload = payload;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        requirePending(now);
        status = Status.COMPLETED;
        updatedAt = now;
    }

    private void requirePending(Instant now) {
        if (status != Status.PENDING) throw new IllegalStateException("school transport draft is not pending");
        if (!expiresAt.isAfter(now)) throw new IllegalStateException("school transport draft expired");
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
}
