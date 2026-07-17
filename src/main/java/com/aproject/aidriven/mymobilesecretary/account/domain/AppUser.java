package com.aproject.aidriven.mymobilesecretary.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppUserStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AppUser() {
    }

    private AppUser(UUID id, String displayName, AppUserStatus status, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = requireText(displayName, "displayName");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    public static AppUser create(String displayName, Instant now) {
        return new AppUser(UUID.randomUUID(), displayName, AppUserStatus.ACTIVE, now);
    }

    public boolean isActive() {
        return status == AppUserStatus.ACTIVE;
    }

    public void suspend(Instant now) {
        status = AppUserStatus.SUSPENDED;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AppUserStatus getStatus() {
        return status;
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
