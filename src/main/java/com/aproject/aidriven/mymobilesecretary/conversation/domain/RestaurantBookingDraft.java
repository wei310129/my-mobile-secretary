package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Typed, actor-private intake for read-only restaurant guidance; never represents a booking. */
@Entity
@Table(name = "restaurant_booking_draft")
public class RestaurantBookingDraft extends WorkspaceOwnedEntity {

    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40)
    private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, length = 64)
    private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false)
    private int scopeKeyVersion;
    @Column(name = "restaurant_name", length = 200)
    private String restaurantName;
    @Column(name = "dining_at")
    private Instant diningAt;
    @Column(name = "party_size")
    private Integer partySize;
    @Column(name = "includes_child", nullable = false)
    private boolean includesChild;
    @Column(name = "requires_accessibility", nullable = false)
    private boolean requiresAccessibility;
    @Column(name = "includes_pet", nullable = false)
    private boolean includesPet;
    @Column(name = "suspended_question_id")
    private UUID suspendedQuestionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RestaurantBookingDraftStatus status;
    @Column(nullable = false)
    private long revision;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RestaurantBookingDraft() {
    }

    public static RestaurantBookingDraft start(
            ConversationScopeKey scope, WorkspaceChannel channel, UUID suspendedQuestionId,
            Instant expiresAt, Instant now) {
        if (!Objects.requireNonNull(expiresAt, "expires at").isAfter(now)) {
            throw new IllegalArgumentException("restaurant draft expiry must be in the future");
        }
        RestaurantBookingDraft draft = new RestaurantBookingDraft();
        draft.id = UUID.randomUUID();
        draft.channel = Objects.requireNonNull(channel, "channel");
        draft.conversationScopeDigest = Objects.requireNonNull(scope, "scope").digest();
        draft.scopeKeyVersion = scope.keyVersion();
        draft.suspendedQuestionId = suspendedQuestionId;
        draft.status = RestaurantBookingDraftStatus.PENDING;
        draft.revision = 1;
        draft.expiresAt = expiresAt;
        draft.createdAt = Objects.requireNonNull(now, "now");
        draft.updatedAt = now;
        return draft;
    }

    public void merge(
            String restaurant, Instant time, Integer people,
            boolean child, boolean accessibility, boolean pet, Instant now) {
        requirePending();
        boolean changed = false;
        if (restaurant != null && !restaurant.isBlank()) {
            String normalized = normalizeRestaurant(restaurant);
            if (!Objects.equals(restaurantName, normalized)) {
                restaurantName = normalized;
                changed = true;
            }
        }
        if (time != null && !Objects.equals(diningAt, time)) {
            diningAt = time;
            changed = true;
        }
        if (people != null) {
            if (people < 1 || people > 100) {
                throw new IllegalArgumentException("party size must be between 1 and 100");
            }
            if (!Objects.equals(partySize, people)) {
                partySize = people;
                changed = true;
            }
        }
        if (child && !includesChild) { includesChild = true; changed = true; }
        if (accessibility && !requiresAccessibility) {
            requiresAccessibility = true;
            changed = true;
        }
        if (pet && !includesPet) { includesPet = true; changed = true; }
        if (changed) touch(now);
    }

    public boolean isComplete() {
        return restaurantName != null && diningAt != null && partySize != null;
    }

    public void complete(Instant now) {
        requirePending();
        if (!isComplete()) throw new IllegalStateException("restaurant draft is incomplete");
        status = RestaurantBookingDraftStatus.COMPLETED;
        touch(now);
    }

    public void cancel(Instant now) {
        requirePending();
        status = RestaurantBookingDraftStatus.CANCELED;
        touch(now);
    }

    public boolean expireIfDue(Instant now) {
        if (status == RestaurantBookingDraftStatus.PENDING && !expiresAt.isAfter(now)) {
            status = RestaurantBookingDraftStatus.EXPIRED;
            touch(now);
            return true;
        }
        return status == RestaurantBookingDraftStatus.EXPIRED;
    }

    private static String normalizeRestaurant(String value) {
        String normalized = value.strip().replaceAll("[\\p{Cntrl}]", "");
        if (normalized.isBlank() || normalized.length() > 200) {
            throw new IllegalArgumentException("restaurant name is invalid");
        }
        return normalized;
    }

    private void requirePending() {
        if (status != RestaurantBookingDraftStatus.PENDING) {
            throw new IllegalStateException("restaurant draft is no longer pending");
        }
    }

    private void touch(Instant now) {
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    public UUID getId() { return id; }
    public String getRestaurantName() { return restaurantName; }
    public Instant getDiningAt() { return diningAt; }
    public Integer getPartySize() { return partySize; }
    public boolean isIncludesChild() { return includesChild; }
    public boolean isRequiresAccessibility() { return requiresAccessibility; }
    public boolean isIncludesPet() { return includesPet; }
    public UUID getSuspendedQuestionId() { return suspendedQuestionId; }
    public RestaurantBookingDraftStatus getStatus() { return status; }
    public long getRevision() { return revision; }
}
