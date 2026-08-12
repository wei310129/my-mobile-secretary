package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusCloseReason;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusHead;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusRootKind;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransition;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusHeadRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.FocusTransitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConversationFocusService {

    private final ConversationScopeResolver resolver;
    private final ConversationFocusHeadRepository heads;
    private final ConversationFocusRepository focuses;
    private final FocusTransitionRepository transitions;
    private final Clock clock;

    public ConversationFocusService(ConversationScopeResolver resolver,
                                    ConversationFocusHeadRepository heads,
                                    ConversationFocusRepository focuses,
                                    FocusTransitionRepository transitions, Clock clock) {
        this.resolver = resolver;
        this.heads = heads;
        this.focuses = focuses;
        this.transitions = transitions;
        this.clock = clock;
    }

    public ConversationFocus enterWorkflow(String domain, UUID workflowId, String label,
                                            String inboundHmac) {
        return enterWorkflow(domain, workflowId, label, null, null, inboundHmac);
    }

    public ConversationFocus enterWorkflow(
            String domain, UUID workflowId, String label,
            String activityCode, String activityLabel, String inboundHmac) {
        return enter(ConversationFocusRootKind.WORKFLOW, domain, null, workflowId, label,
                activityCode, activityLabel, inboundHmac);
    }

    public ConversationFocus enterAsyncWork(String domain, UUID workflowId, String label,
                                            String inboundHmac) {
        return enter(ConversationFocusRootKind.ASYNC_WORK, domain, null, workflowId, label,
                null, null, inboundHmac);
    }

    public ConversationFocus enterResource(String domain, String routingKey, String label,
                                           String inboundHmac) {
        return enter(ConversationFocusRootKind.RESOURCE, domain, routingKey, null, label,
                null, null, inboundHmac);
    }

    public ConversationFocus switchWorkflow(String domain, UUID workflowId, String label,
                                            String inboundHmac) {
        return switchWorkflow(domain, workflowId, label, null, null, inboundHmac);
    }

    public ConversationFocus switchWorkflow(
            String domain, UUID workflowId, String label,
            String activityCode, String activityLabel, String inboundHmac) {
        return switchFocus(ConversationFocusRootKind.WORKFLOW, domain, null, workflowId, label,
                activityCode, activityLabel, inboundHmac);
    }

    public ConversationFocus switchResource(String domain, String routingKey, String label,
                                            String inboundHmac) {
        return switchFocus(ConversationFocusRootKind.RESOURCE, domain, routingKey, null, label,
                null, null, inboundHmac);
    }

    public void exit(String inboundHmac) {
        mutate(FocusTransitionType.EXIT, inboundHmac, null, null);
    }

    public void changeActivity(String code, String label, String inboundHmac) {
        mutate(FocusTransitionType.CHANGE_SUBFOCUS, inboundHmac, code, label);
    }

    public void close(ConversationFocusCloseReason reason, String inboundHmac) {
        mutate(FocusTransitionType.CLOSE, inboundHmac, reason.name(), null);
    }

    /** Closes every conversational focus in this actor/scope without deleting domain resources. */
    public int closeAll(String inboundHmac) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        if (existing(context, scope, inboundHmac) != null) return 0;
        List<ConversationFocus> open = focuses
                .findAllByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatusInOrderByCreatedAtAsc(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        List.of(ConversationFocusStatus.ACTIVE, ConversationFocusStatus.SUSPENDED));
        if (open.isEmpty()) return 0;
        ConversationFocus anchor = open.stream()
                .filter(focus -> focus.getStatus() == ConversationFocusStatus.ACTIVE)
                .findFirst()
                .orElse(open.getFirst());
        Instant now = Instant.now(clock);
        open.forEach(focus -> focus.close(ConversationFocusCloseReason.USER_CLOSED, now));
        focuses.saveAll(open);
        record(context, scope, head(context, scope), FocusTransitionType.CLOSE,
                anchor.getId(), anchor.getId(), inboundHmac, now);
        return open.size();
    }

    public void invalidate(String inboundHmac) {
        mutate(FocusTransitionType.INVALIDATE, inboundHmac,
                ConversationFocusCloseReason.TARGET_INVALIDATED.name(), null);
    }

    /** Invalidates one authorized suspended target without disturbing another active focus. */
    public void invalidate(UUID focusId, String inboundHmac) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        ConversationFocusHead head = head(context, scope);
        if (existing(context, scope, inboundHmac) != null) {
            return;
        }
        ConversationFocus target = focuses
                .findByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                        focusId, context.workspaceId(), context.actorId(), context.channel(),
                        scope.digest())
                .orElseThrow(() -> new IllegalStateException(
                        "focus is not available in this scope"));
        if (target.getStatus() != ConversationFocusStatus.SUSPENDED) {
            throw new IllegalStateException("target focus is not suspended");
        }
        ConversationFocus retained = active(context, scope);
        Instant now = Instant.now(clock);
        target.close(ConversationFocusCloseReason.TARGET_INVALIDATED, now);
        focuses.save(target);
        record(context, scope, head, FocusTransitionType.INVALIDATE,
                target.getId(), retained == null ? target.getId() : retained.getId(),
                inboundHmac, now);
    }

    public boolean hasActiveFocus() {
        return activeFocus().isPresent();
    }

    public Optional<ConversationFocus> activeFocus() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        return Optional.ofNullable(active(context, scope(context)));
    }

    public void keep() {
        activeFocus();
    }

    public void resume(UUID focusId, String inboundHmac) {
        resume(focusId, null, inboundHmac);
    }

    public void resume(UUID focusId, String safeLabel, String inboundHmac) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        ConversationFocusHead head = head(context, scope);
        if (existing(context, scope, inboundHmac) != null) {
            return;
        }
        ConversationFocus focus = focuses
                .findByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                        focusId, context.workspaceId(), context.actorId(), context.channel(), scope.digest())
                .orElseThrow(() -> new IllegalStateException("focus is not available in this scope"));
        if (focus.getStatus() != ConversationFocusStatus.SUSPENDED) {
            throw new IllegalStateException("target focus is not suspended");
        }
        ConversationFocus previous = active(context, scope);
        Instant now = Instant.now(clock);
        if (previous != null) {
            previous.suspend(now);
            focuses.saveAndFlush(previous);
        }
        focus.resume(now, safeLabel);
        focuses.save(focus);
        record(context, scope, head, FocusTransitionType.RESUME,
                previous == null ? focus.getId() : previous.getId(), focus.getId(), inboundHmac, now);
    }

    @Transactional(readOnly = true)
    public Optional<ConversationFocus> suspendedResource(String domain, String routingKey) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        return focuses
                .findFirstByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndRootDomainAndRoutingKeyAndStatusOrderByUpdatedAtDesc(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        domain, routingKey, ConversationFocusStatus.SUSPENDED);
    }

    @Transactional(readOnly = true)
    public Optional<ConversationFocus> suspendedWorkflow(String domain, UUID workflowId) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        return focuses
                .findFirstByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndRootDomainAndWorkflowIdAndStatusOrderByUpdatedAtDesc(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        domain, workflowId, ConversationFocusStatus.SUSPENDED);
    }

    private ConversationFocus enter(ConversationFocusRootKind kind, String domain, String routingKey,
                                    UUID workflowId, String label, String activityCode,
                                    String activityLabel, String inboundHmac) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        ConversationFocusHead head = head(context, scope);
        FocusTransition duplicate = existing(context, scope, inboundHmac);
        if (duplicate != null) {
            return focusFromDuplicate(duplicate);
        }
        if (active(context, scope) != null) {
            throw new IllegalStateException("scope already has an active focus");
        }
        Instant now = Instant.now(clock);
        ConversationFocus focus = createFocus(scope, context, kind, domain, routingKey, workflowId, label, now);
        focus.initializeActivity(activityCode, activityLabel);
        focuses.save(focus);
        record(context, scope, head, FocusTransitionType.ENTER, null, focus.getId(), inboundHmac, now);
        return focus;
    }

    private ConversationFocus switchFocus(ConversationFocusRootKind kind, String domain,
                                          String routingKey, UUID workflowId, String label,
                                          String activityCode, String activityLabel,
                                          String inboundHmac) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        ConversationFocusHead head = head(context, scope);
        FocusTransition duplicate = existing(context, scope, inboundHmac);
        if (duplicate != null) {
            return focusFromDuplicate(duplicate);
        }
        ConversationFocus previous = active(context, scope);
        if (previous == null) {
            throw new IllegalStateException("no active focus to switch");
        }
        Instant now = Instant.now(clock);
        previous.suspend(now);
        focuses.saveAndFlush(previous);
        ConversationFocus next = createFocus(scope, context, kind, domain, routingKey, workflowId, label, now);
        next.initializeActivity(activityCode, activityLabel);
        focuses.save(next);
        record(context, scope, head, FocusTransitionType.SWITCH,
                previous.getId(), next.getId(), inboundHmac, now);
        return next;
    }

    private static ConversationFocus createFocus(ConversationScopeKey scope, WorkspaceContext context,
                                                 ConversationFocusRootKind kind, String domain,
                                                 String routingKey, UUID workflowId, String label,
                                                 Instant now) {
        if (kind == ConversationFocusRootKind.RESOURCE) {
            return ConversationFocus.resource(scope, context.channel(), domain, routingKey, label, now);
        }
        return ConversationFocus.workflow(scope, context.channel(), kind, domain, workflowId, label, now);
    }

    private void mutate(FocusTransitionType type, String inboundHmac, String value, String label) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = scope(context);
        ConversationFocusHead head = head(context, scope);
        if (existing(context, scope, inboundHmac) != null) {
            return;
        }
        ConversationFocus focus = active(context, scope);
        if (focus == null) {
            throw new IllegalStateException("no active focus");
        }
        Instant now = Instant.now(clock);
        if (type == FocusTransitionType.EXIT) {
            focus.suspend(now);
        } else if (type == FocusTransitionType.CHANGE_SUBFOCUS) {
            focus.changeActivity(value, label, now);
        } else if (type == FocusTransitionType.CLOSE || type == FocusTransitionType.INVALIDATE) {
            focus.close(ConversationFocusCloseReason.valueOf(value), now);
        } else {
            throw new IllegalArgumentException("unsupported focus transition");
        }
        focuses.save(focus);
        record(context, scope, head, type, focus.getId(), focus.getId(), inboundHmac, now);
    }

    private ConversationFocus active(WorkspaceContext context, ConversationScopeKey scope) {
        return focuses.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                ConversationFocusStatus.ACTIVE).orElse(null);
    }

    private ConversationScopeKey scope(WorkspaceContext context) {
        ConversationScopeKey scope = resolver.current(context);
        if (scope == null) {
            throw new IllegalStateException("conversation scope is unavailable");
        }
        return scope;
    }

    private FocusTransition existing(WorkspaceContext context, ConversationScopeKey scope, String inboundHmac) {
        return transitions.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndInboundIdempotencyHmac(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest(), inboundHmac)
                .orElse(null);
    }

    private ConversationFocus focusFromDuplicate(FocusTransition transition) {
        if (transition.getToFocusId() == null) {
            throw new IllegalStateException("duplicate transition has no resulting focus");
        }
        return focuses.findById(transition.getToFocusId())
                .orElseThrow(() -> new IllegalStateException("duplicate transition focus is unavailable"));
    }

    private ConversationFocusHead head(WorkspaceContext context, ConversationScopeKey scope) {
        return heads.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest())
                .orElseGet(() -> heads.save(ConversationFocusHead.create(scope, context.channel(),
                        Instant.now(clock))));
    }

    private void record(WorkspaceContext context, ConversationScopeKey scope, ConversationFocusHead head,
                        FocusTransitionType type, UUID from, UUID to, String inboundHmac, Instant now) {
        long before = head.getRevision();
        long after = head.advance(before, now);
        heads.save(head);
        transitions.save(FocusTransition.create(scope, context.channel(), before, after, type,
                from, to, inboundHmac, now));
    }
}
