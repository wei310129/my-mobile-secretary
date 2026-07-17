package com.aproject.aidriven.mymobilesecretary.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "external_identity", uniqueConstraints = {
        @UniqueConstraint(name = "uq_external_identity_provider_subject", columnNames = {"provider", "subject"}),
        @UniqueConstraint(name = "uq_external_identity_user_provider", columnNames = {"user_id", "provider"})
})
public class ExternalIdentity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(nullable = false, length = 255)
    private String subject;

    private UUID defaultWorkspaceId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ExternalIdentity() {
    }

    private ExternalIdentity(UUID id, UUID userId, String provider, String subject,
                             UUID defaultWorkspaceId, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.provider = normalizeProvider(provider);
        this.subject = requireText(subject, "subject");
        this.defaultWorkspaceId = defaultWorkspaceId;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public static ExternalIdentity create(UUID userId, String provider, String subject, Instant now) {
        return new ExternalIdentity(UUID.randomUUID(), userId, provider, subject, null, now);
    }

    public static ExternalIdentity create(UUID userId, String provider, String subject,
                                          UUID defaultWorkspaceId, Instant now) {
        return new ExternalIdentity(UUID.randomUUID(), userId, provider, subject,
                Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId"), now);
    }

    public void selectDefaultWorkspace(UUID workspaceId, Instant now) {
        this.defaultWorkspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getDefaultWorkspaceId() {
        return defaultWorkspaceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String normalizeProvider(String provider) {
        return requireText(provider, "provider").toUpperCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
