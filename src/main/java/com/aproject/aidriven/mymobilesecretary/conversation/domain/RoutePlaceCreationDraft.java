package com.aproject.aidriven.mymobilesecretary.conversation.domain;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceOwnedEntity;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
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

/** Typed place candidate required by one route; never stores the inbound LINE message. */
@Entity
@Table(name = "route_place_creation_draft")
public class RoutePlaceCreationDraft extends WorkspaceOwnedEntity {

    @Id private UUID id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 40)
    private WorkspaceChannel channel;
    @Column(name = "conversation_scope_digest", nullable = false, updatable = false, length = 64)
    private String conversationScopeDigest;
    @Column(name = "scope_key_version", nullable = false, updatable = false)
    private Integer scopeKeyVersion;
    @Column(name = "parent_calendar_draft_id", nullable = false, updatable = false)
    private UUID parentCalendarDraftId;
    @Column(name = "parent_calendar_draft_revision", nullable = false, updatable = false)
    private long parentCalendarDraftRevision;
    @Column(name = "requested_alias", nullable = false, updatable = false, length = 80)
    private String requestedAlias;
    @Column(name = "place_query", length = 300)
    private String placeQuery;
    @Column(name = "candidate_name", length = 200)
    private String candidateName;
    @Column(name = "candidate_address", length = 500)
    private String candidateAddress;
    @Column(name = "candidate_latitude") private Double candidateLatitude;
    @Column(name = "candidate_longitude") private Double candidateLongitude;
    @Column(name = "candidate_type", length = 100)
    private String candidateType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RoutePlaceCreationStep step;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private RoutePlaceCreationDraftStatus status;
    @Column(nullable = false) private long revision;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Version private Long version;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected RoutePlaceCreationDraft() {}

    public static RoutePlaceCreationDraft create(
            ConversationScopeKey scope,
            WorkspaceChannel channel,
            UUID parentCalendarDraftId,
            long parentCalendarDraftRevision,
            String requestedAlias,
            String placeQuery,
            PlaceService.ResolvedPlaceCandidate candidate,
            Instant expiresAt,
            Instant now) {
        RoutePlaceCreationDraft draft = new RoutePlaceCreationDraft();
        draft.id = UUID.randomUUID();
        draft.channel = Objects.requireNonNull(channel, "channel");
        draft.conversationScopeDigest = Objects.requireNonNull(scope, "scope").digest();
        draft.scopeKeyVersion = scope.keyVersion();
        draft.parentCalendarDraftId = Objects.requireNonNull(parentCalendarDraftId, "parent draft");
        if (parentCalendarDraftRevision < 1) {
            throw new IllegalArgumentException("parent draft revision must be positive");
        }
        draft.parentCalendarDraftRevision = parentCalendarDraftRevision;
        draft.requestedAlias = required(requestedAlias, 80, "requested alias");
        draft.setCandidate(placeQuery, candidate);
        draft.step = RoutePlaceCreationStep.CONFIRM;
        draft.status = RoutePlaceCreationDraftStatus.PENDING;
        draft.revision = 1;
        draft.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
        draft.createdAt = Objects.requireNonNull(now, "now");
        draft.updatedAt = now;
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("route place draft expiry must be in the future");
        }
        return draft;
    }

    public static RoutePlaceCreationDraft offer(
            ConversationScopeKey scope,
            WorkspaceChannel channel,
            UUID parentCalendarDraftId,
            long parentCalendarDraftRevision,
            String requestedAlias,
            Instant expiresAt,
            Instant now) {
        RoutePlaceCreationDraft draft = new RoutePlaceCreationDraft();
        draft.id = UUID.randomUUID();
        draft.channel = Objects.requireNonNull(channel, "channel");
        draft.conversationScopeDigest = Objects.requireNonNull(scope, "scope").digest();
        draft.scopeKeyVersion = scope.keyVersion();
        draft.parentCalendarDraftId = Objects.requireNonNull(parentCalendarDraftId, "parent draft");
        if (parentCalendarDraftRevision < 1) {
            throw new IllegalArgumentException("parent draft revision must be positive");
        }
        draft.parentCalendarDraftRevision = parentCalendarDraftRevision;
        draft.requestedAlias = required(requestedAlias, 80, "requested alias");
        draft.step = RoutePlaceCreationStep.OFFER;
        draft.status = RoutePlaceCreationDraftStatus.PENDING;
        draft.revision = 1;
        draft.expiresAt = Objects.requireNonNull(expiresAt, "expires at");
        draft.createdAt = Objects.requireNonNull(now, "now");
        draft.updatedAt = now;
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("route place draft expiry must be in the future");
        }
        return draft;
    }

    public void requestDetails(Instant now) {
        requirePending(now);
        if (step != RoutePlaceCreationStep.OFFER) {
            throw new IllegalStateException("route place offer is unavailable");
        }
        step = RoutePlaceCreationStep.DETAILS;
        touch(now);
    }

    public void confirmCandidate(
            String query, PlaceService.ResolvedPlaceCandidate candidate, Instant now) {
        requirePending(now);
        if (step != RoutePlaceCreationStep.OFFER && step != RoutePlaceCreationStep.DETAILS) {
            throw new IllegalStateException("route place details are unavailable");
        }
        setCandidate(query, candidate);
        step = RoutePlaceCreationStep.CONFIRM;
        touch(now);
    }

    public void complete(Instant now) {
        requirePending(now);
        status = RoutePlaceCreationDraftStatus.COMPLETED;
        touch(now);
    }

    public void cancel(Instant now) {
        requirePending(now);
        status = RoutePlaceCreationDraftStatus.CANCELED;
        touch(now);
    }

    public boolean expireIfDue(Instant now) {
        if (status == RoutePlaceCreationDraftStatus.PENDING && !expiresAt.isAfter(now)) {
            status = RoutePlaceCreationDraftStatus.EXPIRED;
            touch(now);
            return true;
        }
        return false;
    }

    public PlaceService.ResolvedPlaceCandidate candidate() {
        if (step != RoutePlaceCreationStep.CONFIRM
                || candidateName == null
                || candidateLatitude == null
                || candidateLongitude == null) {
            throw new IllegalStateException("route place candidate is unavailable");
        }
        return new PlaceService.ResolvedPlaceCandidate(
                candidateName,
                candidateAddress,
                candidateLatitude,
                candidateLongitude,
                candidateType);
    }

    private void setCandidate(String query, PlaceService.ResolvedPlaceCandidate candidate) {
        PlaceService.ResolvedPlaceCandidate requiredCandidate =
                Objects.requireNonNull(candidate, "candidate");
        placeQuery = required(query, 300, "place query");
        candidateName = required(requiredCandidate.name(), 200, "candidate name");
        candidateAddress = bounded(requiredCandidate.address(), 500, "candidate address");
        candidateLatitude = requiredCandidate.latitude();
        candidateLongitude = requiredCandidate.longitude();
        candidateType = bounded(requiredCandidate.type(), 100, "candidate type");
    }

    private void requirePending(Instant now) {
        if (status != RoutePlaceCreationDraftStatus.PENDING || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("route place creation draft is unavailable");
        }
    }

    private void touch(Instant now) {
        revision++;
        updatedAt = Objects.requireNonNull(now, "now");
    }

    private static String required(String value, int max, String field) {
        String bounded = bounded(value, max, field);
        if (bounded == null || bounded.isBlank()) throw new IllegalArgumentException(field + " is required");
        return bounded;
    }

    private static String bounded(String value, int max, String field) {
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.length() > max || stripped.indexOf('\n') >= 0 || stripped.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return stripped.isBlank() ? null : stripped;
    }

    public UUID getId() { return id; }
    public UUID getParentCalendarDraftId() { return parentCalendarDraftId; }
    public long getParentCalendarDraftRevision() { return parentCalendarDraftRevision; }
    public String getRequestedAlias() { return requestedAlias; }
    public String getPlaceQuery() { return placeQuery; }
    public String getCandidateName() { return candidateName; }
    public RoutePlaceCreationStep getStep() { return step; }
    public RoutePlaceCreationDraftStatus getStatus() { return status; }
    public long getRevision() { return revision; }
}
