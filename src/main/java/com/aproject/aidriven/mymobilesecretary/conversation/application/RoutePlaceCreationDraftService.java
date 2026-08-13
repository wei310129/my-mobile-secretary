package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RoutePlaceCreationDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.RoutePlaceCreationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.geo.domain.ResolvedPlaceCandidate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable Route -> Place child workflow.
 *
 * <p>A child stores only a typed, already-resolved candidate. It never stores the inbound LINE
 * message or a generic slot bag. Completing a child updates its parent route's origin but does
 * not materialize the parent plan; the route remains responsible for provider evidence and its
 * own completion gate.</p>
 */
@Service
@Transactional
public class RoutePlaceCreationDraftService {

    public static final String ROOT_DOMAIN = "ROUTE_PLACE_CREATION";
    private static final Duration RETENTION = Duration.ofDays(7);

    private final ConversationScopeResolver scopes;
    private final RoutePlaceCreationDraftRepository drafts;
    private final CalendarIntentDraftService routes;
    private final Clock clock;

    public RoutePlaceCreationDraftService(
            ConversationScopeResolver scopes,
            RoutePlaceCreationDraftRepository drafts,
            CalendarIntentDraftService routes,
            Clock clock) {
        this.scopes = scopes;
        this.drafts = drafts;
        this.routes = routes;
        this.clock = clock;
    }

    /** Starts or replays the one child bound to the current route and conversation scope. */
    public RoutePlaceCreationDraft startCandidate(
            UUID parentDraftId,
            long expectedParentRevision,
            String requestedAlias,
            String query,
            ResolvedPlaceCandidate candidate) {
        Objects.requireNonNull(candidate, "resolved place candidate");
        CalendarIntentDraftService.DraftView parent = routes.get(parentDraftId);
        if (parent.status() != CalendarIntentDraftService.Status.PENDING
                || parent.revision() != expectedParentRevision) {
            throw new IllegalStateException("route parent changed before place creation");
        }
        WorkspaceContext context = tenantContext();
        ConversationScopeKey scope = scopes.current(context);
        Instant now = Instant.now(clock);
        Optional<RoutePlaceCreationDraft> current = currentLocked(context, scope);
        if (current.isPresent()) {
            RoutePlaceCreationDraft existing = current.get();
            if (!existing.expireIfDue(now)
                    && existing.getParentCalendarDraftId().equals(parentDraftId)
                    && existing.getParentCalendarDraftRevision() == expectedParentRevision
                    && existing.getRequestedAlias().equalsIgnoreCase(requestedAlias)) {
                return existing;
            }
            if (existing.getStatus() == RoutePlaceCreationDraftStatus.PENDING) {
                existing.cancel(now);
                drafts.saveAndFlush(existing);
            }
        }
        return drafts.saveAndFlush(RoutePlaceCreationDraft.create(
                scope,
                context.channel(),
                parentDraftId,
                expectedParentRevision,
                requestedAlias,
                query,
                candidate,
                now.plus(RETENTION),
                now));
    }

    /**
     * Uses the child candidate for this route only. Persistent personal-place recording remains a
     * separately authorized operation and is intentionally not implied by this route mutation.
     */
    public Completion completeForThisRoute(UUID childDraftId) {
        RoutePlaceCreationDraft child = loadCurrentScope(childDraftId, true).orElseThrow(
                () -> new IllegalStateException("route place child is unavailable"));
        Instant now = Instant.now(clock);
        if (child.expireIfDue(now)) {
            drafts.saveAndFlush(child);
            throw new IllegalStateException("route place child expired");
        }
        if (child.getStatus() == RoutePlaceCreationDraftStatus.COMPLETED) {
            CalendarIntentDraftService.DraftView parent = routes.get(child.getParentCalendarDraftId());
            return new Completion(parent, child, true);
        }
        if (child.getStatus() != RoutePlaceCreationDraftStatus.PENDING) {
            throw new IllegalStateException("route place child is unavailable");
        }
        ResolvedPlaceCandidate candidate = child.candidate();
        CalendarIntentDraftService.DraftView parent = routes.setOriginFromChild(
                child.getParentCalendarDraftId(),
                child.getParentCalendarDraftRevision(),
                new CalendarLocation(candidate.name(), candidate.latitude(), candidate.longitude()),
                CalendarIntentDraftService.EndpointSource.EXPLICIT_CURRENT_TURN);
        child.complete(now);
        drafts.saveAndFlush(child);
        return new Completion(parent, child, false);
    }

    /** Idempotently closes only the child; its parent remains available to resume. */
    public boolean cancel(UUID childDraftId) {
        Optional<RoutePlaceCreationDraft> found = loadCurrentScope(childDraftId, true);
        if (found.isEmpty()) {
            return false;
        }
        RoutePlaceCreationDraft child = found.get();
        Instant now = Instant.now(clock);
        if (child.expireIfDue(now)) {
            drafts.saveAndFlush(child);
            return false;
        }
        if (child.getStatus() != RoutePlaceCreationDraftStatus.PENDING) {
            return false;
        }
        child.cancel(now);
        drafts.saveAndFlush(child);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<RoutePlaceCreationDraft> findAvailableForLifecycle(UUID childDraftId) {
        Optional<RoutePlaceCreationDraft> found = loadCurrentScope(childDraftId, false);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RoutePlaceCreationDraft child = found.get();
        return child.isPendingAt(Instant.now(clock))
                ? Optional.of(child)
                : Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<RoutePlaceCreationDraft> current() {
        WorkspaceContext context = tenantContext();
        return currentLocked(context, scopes.current(context));
    }

    private Optional<RoutePlaceCreationDraft> loadCurrentScope(UUID id, boolean lock) {
        if (id == null) {
            return Optional.empty();
        }
        WorkspaceContext context = tenantContext();
        ConversationScopeKey scope = scopes.current(context);
        return lock
                ? drafts.findWithLockByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                        id, context.workspaceId(), context.actorId(), context.channel(), scope.digest())
                : drafts.findByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                        id, context.workspaceId(), context.actorId(), context.channel(), scope.digest());
    }

    private Optional<RoutePlaceCreationDraft> currentLocked(
            WorkspaceContext context, ConversationScopeKey scope) {
        return drafts.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                context.workspaceId(),
                context.actorId(),
                context.channel(),
                scope.digest(),
                RoutePlaceCreationDraftStatus.PENDING);
    }

    private static WorkspaceContext tenantContext() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException("Route place creation requires a tenant workspace");
        }
        return context;
    }

    public record Completion(
            CalendarIntentDraftService.DraftView parent,
            RoutePlaceCreationDraft child,
            boolean replayed) {}
}
