package com.aproject.aidriven.mymobilesecretary.media.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Actor-private metadata for an original uploaded object; bytes live behind object storage. */
@Entity
public class StoredMedia extends WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_kind", nullable = false, length = 20)
    private MediaKind mediaKind;

    @Column(nullable = false, length = 255)
    private String displayName;

    @Column(length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 120)
    private String mediaType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, unique = true, length = 300)
    private String storageKey;

    @Column(length = 255)
    private String sourceReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    private Instant deletedAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected StoredMedia() {
    }

    public static StoredMedia create(SourceType sourceType, MediaKind mediaKind,
                                     String displayName, String originalFilename,
                                     String mediaType, long sizeBytes, String sha256,
                                     String storageKey, String sourceReference, Instant now) {
        StoredMedia media = new StoredMedia();
        media.sourceType = sourceType;
        media.mediaKind = mediaKind;
        media.displayName = displayName;
        media.originalFilename = originalFilename;
        media.mediaType = mediaType;
        media.sizeBytes = sizeBytes;
        media.sha256 = sha256;
        media.storageKey = storageKey;
        media.sourceReference = sourceReference;
        media.status = Status.AVAILABLE;
        media.createdAt = now;
        return media;
    }

    public void markDeleted(Instant now) {
        if (status == Status.DELETED) return;
        status = Status.DELETED;
        deletedAt = now;
    }

    public void rename(String value) {
        if (value == null || value.isBlank()) return;
        displayName = value.strip().length() <= 255
                ? value.strip() : value.strip().substring(0, 255);
    }

    public Long getId() {
        return id;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public MediaKind getMediaKind() {
        return mediaKind;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public enum SourceType {
        LINE,
        APP
    }

    public enum MediaKind {
        IMAGE,
        DOCUMENT
    }

    public enum Status {
        AVAILABLE,
        DELETED
    }
}
