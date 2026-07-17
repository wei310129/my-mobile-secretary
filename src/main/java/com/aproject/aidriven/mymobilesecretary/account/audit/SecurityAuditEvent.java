package com.aproject.aidriven.mymobilesecretary.account.audit;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Minimal security audit metadata. Raw messages, credentials and tokens never belong here. */
@Entity
public class SecurityAuditEvent {

    public enum Outcome {
        SUCCEEDED, DENIED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID eventId;

    private UUID workspaceId;
    private UUID actorUserId;

    @Column(nullable = false, length = 80)
    private String eventType;

    @Column(length = 80)
    private String targetType;

    @Column(length = 160)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Outcome outcome;

    @Column(length = 80)
    private String reasonCode;

    @Column(nullable = false, length = 40)
    private String channel;

    private UUID requestId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected SecurityAuditEvent() {
    }

    static SecurityAuditEvent from(SecurityAuditDraft draft, Instant now, Instant expiresAt) {
        SecurityAuditEvent event = new SecurityAuditEvent();
        event.eventId = UUID.randomUUID();
        event.workspaceId = draft.workspaceId();
        event.actorUserId = draft.actorUserId();
        event.eventType = required(draft.eventType(), "eventType", 80);
        event.targetType = optional(draft.targetType(), 80);
        event.targetId = optional(draft.targetId(), 160);
        event.outcome = draft.outcome() == null ? Outcome.FAILED : draft.outcome();
        event.reasonCode = optional(draft.reasonCode(), 80);
        event.channel = required(draft.channel(), "channel", 40);
        event.requestId = draft.requestId();
        event.createdAt = now;
        event.expiresAt = expiresAt;
        return event;
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return optional(value, maxLength);
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip().replace('\n', ' ').replace('\r', ' ');
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getEventType() {
        return eventType;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
