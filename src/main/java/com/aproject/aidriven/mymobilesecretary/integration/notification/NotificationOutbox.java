package com.aproject.aidriven.mymobilesecretary.integration.notification;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class NotificationOutbox extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(nullable = false, length = 250, updatable = false)
    private String deliveryKey;

    @Column(nullable = false, updatable = false)
    private UUID targetUserId;

    @Column(nullable = false, length = 20, updatable = false)
    private String channel;

    @Column(nullable = false, length = 500, updatable = false)
    private String destination;

    private Long reminderId;
    private Long taskId;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, updatable = false)
    private boolean retrySafe;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationOutboxStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant availableAt;

    private Instant claimedUntil;
    private UUID claimToken;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant sentAt;
    private Instant terminalAt;

    protected NotificationOutbox() {
    }

    void claim(UUID token, Instant leaseUntil) {
        if (status != NotificationOutboxStatus.PENDING) {
            throw new IllegalStateException("Only pending notifications can be claimed");
        }
        status = NotificationOutboxStatus.SENDING;
        attemptCount++;
        claimToken = token;
        claimedUntil = leaseUntil;
        lastError = null;
    }

    void retry(Instant availableAt, String errorCode) {
        requireSending();
        status = NotificationOutboxStatus.PENDING;
        this.availableAt = availableAt;
        this.lastError = bounded(errorCode);
        clearClaim();
    }

    void markSent(Instant now) {
        requireSending();
        status = NotificationOutboxStatus.SENT;
        sentAt = now;
        terminalAt = now;
        lastError = null;
        clearClaim();
        clearSensitivePayload();
    }

    void deadLetter(Instant now, String errorCode) {
        if (status != NotificationOutboxStatus.SENDING) {
            throw new IllegalStateException("Only claimed notifications can become dead letters");
        }
        status = NotificationOutboxStatus.DEAD_LETTER;
        terminalAt = now;
        lastError = bounded(errorCode);
        clearClaim();
        clearSensitivePayload();
    }

    ReminderNotification envelope() {
        if (status != NotificationOutboxStatus.SENDING || title == null || message == null) {
            throw new IllegalStateException("Claimed notification payload is unavailable");
        }
        return new ReminderNotification(getWorkspaceId(), targetUserId, deliveryId, destination,
                reminderId, taskId, title, message);
    }

    boolean claimMatches(UUID token) {
        return status == NotificationOutboxStatus.SENDING && token != null && token.equals(claimToken);
    }

    private void requireSending() {
        if (status != NotificationOutboxStatus.SENDING) {
            throw new IllegalStateException("Notification is not claimed");
        }
    }

    private void clearClaim() {
        claimToken = null;
        claimedUntil = null;
    }

    private void clearSensitivePayload() {
        title = null;
        message = null;
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500);
    }

    Long getId() { return id; }
    String getChannel() { return channel; }
    Long getReminderId() { return reminderId; }
    boolean isRetrySafe() { return retrySafe; }
    int getAttemptCount() { return attemptCount; }
    Instant getClaimedUntil() { return claimedUntil; }
    NotificationOutboxStatus getStatus() { return status; }
    String getTitle() { return title; }
    String getMessage() { return message; }
    Instant getAvailableAt() { return availableAt; }
    Instant getTerminalAt() { return terminalAt; }
}
