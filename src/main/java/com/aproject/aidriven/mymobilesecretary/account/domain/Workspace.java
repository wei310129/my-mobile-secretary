package com.aproject.aidriven.mymobilesecretary.account.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "workspace")
public class Workspace {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkspaceType type;

    @Column(nullable = false)
    private UUID createdByUserId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Workspace() {
    }

    private Workspace(UUID id, String name, WorkspaceType type, UUID createdByUserId, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = requireText(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.createdByUserId = Objects.requireNonNull(createdByUserId, "createdByUserId");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public static Workspace createPersonal(UUID userId, String name, Instant now) {
        return new Workspace(UUID.randomUUID(), name, WorkspaceType.PERSONAL, userId, now);
    }

    public static Workspace createHousehold(UUID creatorUserId, String name, Instant now) {
        return new Workspace(UUID.randomUUID(), name, WorkspaceType.HOUSEHOLD, creatorUserId, now);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public WorkspaceType getType() {
        return type;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }
}
