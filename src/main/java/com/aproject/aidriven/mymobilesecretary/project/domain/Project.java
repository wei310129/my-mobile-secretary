package com.aproject.aidriven.mymobilesecretary.project.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Generic coordination aggregate; child ownership is introduced only in later wheels. */
@Entity
@Table(name = "project")
public class Project extends WorkspaceOwnedEntity {

    private static final Pattern HMAC = Pattern.compile("[0-9a-f]{64}");

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "project_type", nullable = false, updatable = false, length = 30)
    private ProjectType type;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Column(name = "creation_request_hmac", nullable = false, updatable = false, length = 64)
    private String creationRequestHmac;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;
    private Instant archivedAt;

    protected Project() {
    }

    private Project(ProjectType type, String name, String creationRequestHmac, Instant now) {
        id = UUID.randomUUID();
        this.type = Objects.requireNonNull(type, "project type is required");
        this.name = normalizeName(name);
        this.creationRequestHmac = requireHmac(creationRequestHmac);
        status = ProjectStatus.ACTIVE;
        createdAt = Objects.requireNonNull(now, "createdAt is required");
        updatedAt = now;
    }

    public static Project create(ProjectType type, String name, String creationRequestHmac,
                                 Instant now) {
        return new Project(type, name, creationRequestHmac, now);
    }

    public void rename(String nextName, Instant now) {
        if (status == ProjectStatus.ARCHIVED) {
            throw new BusinessException("INVALID_PROJECT_STATE",
                    "archived project cannot be renamed");
        }
        name = normalizeName(nextName);
        updatedAt = Objects.requireNonNull(now, "updatedAt is required");
    }

    public void complete(Instant now) {
        requireStatus(ProjectStatus.ACTIVE, "complete");
        status = ProjectStatus.COMPLETED;
        completedAt = Objects.requireNonNull(now, "completedAt is required");
        updatedAt = now;
    }

    public void reopen(Instant now) {
        requireStatus(ProjectStatus.COMPLETED, "reopen");
        status = ProjectStatus.ACTIVE;
        completedAt = null;
        updatedAt = Objects.requireNonNull(now, "reopenedAt is required");
    }

    public void archive(Instant now) {
        if (status == ProjectStatus.ARCHIVED) {
            throw invalidTransition("archive");
        }
        status = ProjectStatus.ARCHIVED;
        archivedAt = Objects.requireNonNull(now, "archivedAt is required");
        updatedAt = now;
    }

    public boolean matchesCreation(ProjectType requestedType, String requestedName) {
        return type == requestedType && name.equals(normalizeName(requestedName));
    }

    private void requireStatus(ProjectStatus required, String operation) {
        if (status != required) {
            throw invalidTransition(operation);
        }
    }

    private BusinessException invalidTransition(String operation) {
        return new BusinessException("INVALID_PROJECT_STATE",
                "Project cannot %s while status is %s".formatted(operation, status));
    }

    private static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("project name is required");
        }
        String normalized = value.strip();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("project name exceeds 200 characters");
        }
        return normalized;
    }

    private static String requireHmac(String value) {
        if (value == null || !HMAC.matcher(value.toLowerCase(Locale.ROOT)).matches()) {
            throw new IllegalArgumentException("creation request HMAC must be 64 lowercase hex characters");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public ProjectType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public String getCreationRequestHmac() {
        return creationRequestHmac;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
