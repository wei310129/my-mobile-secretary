package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.geo.domain.SystemPlaceCategory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Actor-private system-place candidates; contains catalog keys, never raw conversation text. */
@Entity
@Table(name = "public_place_lookup_draft")
public class PublicPlaceLookupDraft extends WorkspaceOwnedEntity {

    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 30)
    private PublicPlaceLookupDraftMode mode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false, updatable = false)
    private Integer scopeKeyVersion;
    @Enumerated(EnumType.STRING) @Column(length = 40)
    private SystemPlaceCategory category;
    @Column(name = "candidate_keys", nullable = false, updatable = false, length = 4000)
    private String candidateKeys;
    @Column(name = "selected_key", length = 200)
    private String selectedKey;
    @Column(name = "calendar_draft_id")
    private UUID calendarDraftId;
    @Column(name = "calendar_draft_revision")
    private Long calendarDraftRevision;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private PublicPlaceLookupDraftStatus status;
    @Column(nullable = false) private long revision;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected PublicPlaceLookupDraft() {
    }

    public static PublicPlaceLookupDraft create(
            ConversationScopeKey scope, WorkspaceChannel channel,
            PublicPlaceLookupDraftMode mode, SystemPlaceCategory category,
            List<String> candidateKeys, CalendarDraftReference calendarDraft,
            Instant expiresAt, Instant now) {
        PublicPlaceLookupDraft draft = new PublicPlaceLookupDraft();
        draft.id = UUID.randomUUID();
        draft.mode = Objects.requireNonNull(mode, "mode");
        draft.channel = Objects.requireNonNull(channel, "channel");
        draft.conversationScopeDigest = scope.digest();
        draft.scopeKeyVersion = scope.keyVersion();
        draft.category = category;
        draft.candidateKeys = encoded(candidateKeys);
        if (mode == PublicPlaceLookupDraftMode.CALENDAR_LOCATION && calendarDraft == null) {
            throw new IllegalArgumentException("calendar place lookup requires a typed calendar draft");
        }
        if (mode != PublicPlaceLookupDraftMode.CALENDAR_LOCATION && calendarDraft != null) {
            throw new IllegalArgumentException("read-only place lookup cannot reference a calendar draft");
        }
        if (calendarDraft != null) {
            draft.calendarDraftId = calendarDraft.id();
            draft.calendarDraftRevision = calendarDraft.revision();
        }
        draft.status = PublicPlaceLookupDraftStatus.PENDING;
        draft.revision = 1;
        if (!Objects.requireNonNull(expiresAt, "expires at").isAfter(now)) {
            throw new IllegalArgumentException("public place draft expiry must be in the future");
        }
        draft.expiresAt = expiresAt;
        draft.createdAt = now;
        draft.updatedAt = now;
        return draft;
    }

    public void complete(String key, Instant now) {
        requirePending(now);
        if (!candidateKeyList().contains(key)) {
            throw new IllegalArgumentException("selected system place is not a draft candidate");
        }
        selectedKey = key;
        status = PublicPlaceLookupDraftStatus.COMPLETED;
        touch(now);
    }

    public void cancel(Instant now) {
        requirePending(now);
        status = PublicPlaceLookupDraftStatus.CANCELED;
        touch(now);
    }

    public boolean expireIfDue(Instant now) {
        if (status == PublicPlaceLookupDraftStatus.PENDING && !expiresAt.isAfter(now)) {
            status = PublicPlaceLookupDraftStatus.EXPIRED;
            touch(now);
            return true;
        }
        return false;
    }

    private void requirePending(Instant now) {
        if (status != PublicPlaceLookupDraftStatus.PENDING || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("public place lookup draft is unavailable");
        }
    }

    private void touch(Instant now) {
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String encoded(List<String> keys) {
        List<String> normalized = keys == null ? List.of() : keys.stream()
                .map(String::strip).distinct().sorted().toList();
        if (normalized.isEmpty()
                || normalized.stream().anyMatch(key -> key.isBlank() || key.contains("|")
                        || key.chars().anyMatch(Character::isWhitespace))) {
            throw new IllegalArgumentException("public place candidates must be stable catalog keys");
        }
        String encoded = String.join("|", normalized);
        if (encoded.length() > 4000) {
            throw new IllegalArgumentException("public place candidate set is too large");
        }
        return encoded;
    }

    public List<String> candidateKeyList() {
        return List.of(candidateKeys.split("[|]"));
    }

    public UUID getId() { return id; }
    public PublicPlaceLookupDraftMode getMode() { return mode; }
    public SystemPlaceCategory getCategory() { return category; }
    public PublicPlaceLookupDraftStatus getStatus() { return status; }
    public long getRevision() { return revision; }
    public UUID getCalendarDraftId() { return calendarDraftId; }
    public Long getCalendarDraftRevision() { return calendarDraftRevision; }

    public record CalendarDraftReference(UUID id, long revision) {
        public CalendarDraftReference {
            Objects.requireNonNull(id, "calendar draft id");
            if (revision < 1) throw new IllegalArgumentException("calendar draft revision must be positive");
        }
    }
}
