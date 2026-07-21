package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusHead;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PendingFocusCandidate;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PendingFocusTransition;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PendingFocusTransitionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusHeadRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.PendingFocusTransitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores an inert typed focus candidate and accepts it only against the original focus-head revision. */
@Service
public class PendingFocusTransitionService {

    private final ConversationScopeResolver resolver;
    private final ConversationFocusHeadRepository heads;
    private final ConversationFocusRepository focuses;
    private final PendingFocusTransitionRepository pendingTransitions;
    private final ConversationFocusAtomicExecutor executor;
    private final Clock clock;

    public PendingFocusTransitionService(ConversationScopeResolver resolver,
                                         ConversationFocusHeadRepository heads,
                                         ConversationFocusRepository focuses,
                                         PendingFocusTransitionRepository pendingTransitions,
                                         ConversationFocusAtomicExecutor executor, Clock clock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.heads = Objects.requireNonNull(heads, "heads");
        this.focuses = Objects.requireNonNull(focuses, "focuses");
        this.pendingTransitions = Objects.requireNonNull(pendingTransitions, "pendingTransitions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public PendingFocusTransition propose(FocusDecision decision, FocusControl control,
                                           String inboundIdempotencyHmac) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        PendingFocusCandidate candidate = candidate(decision, control);
        ConversationFocusHead head = head(context, scope);
        Instant now = Instant.now(clock);
        pendingTransitions.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        PendingFocusTransitionStatus.PENDING)
                .ifPresent(existing -> {
                    existing.reject(now);
                    pendingTransitions.saveAndFlush(existing);
                });
        ConversationFocus active = candidate.transitionType() == FocusTransitionType.SWITCH
                ? active(context, scope) : null;
        if (candidate.transitionType() == FocusTransitionType.SWITCH && active == null) {
            throw new IllegalStateException("no active focus to switch");
        }
        UUID fromFocusId = active == null ? null : active.getId();
        return pendingTransitions.save(PendingFocusTransition.pending(scope, context.channel(),
                head.getRevision(), fromFocusId, candidate, inboundIdempotencyHmac, now));
    }

    @Transactional(noRollbackFor = PendingFocusTransitionExpiredException.class)
    public FocusResponseEnvelope accept(UUID pendingId, Supplier<FocusResponseEnvelope> domainMutation) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        PendingFocusTransition pending = pendingTransitions
                .findWithLockByIdAndWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                        pendingId, context.workspaceId(), context.actorId(), context.channel(), scope.digest())
                .orElseThrow(() -> new IllegalStateException("pending focus transition is unavailable"));
        ConversationFocusHead head = head(context, scope);
        Instant now = Instant.now(clock);
        if (pending.expireIfIncompatibleScope(scope, now)
                || pending.expireIfRevisionChanged(head.getRevision(), now)) {
            pendingTransitions.save(pending);
            throw new PendingFocusTransitionExpiredException();
        }
        if (pending.getStatus() != PendingFocusTransitionStatus.PENDING) {
            throw new IllegalStateException("pending focus transition is unavailable");
        }
        PendingFocusCandidate candidate = pending.getCandidate();
        FocusControl control = control(candidate);
        FocusTransitionNotice notice = notice(context, scope, pending, candidate);
        FocusResponseEnvelope reply = executor.execute(FocusDecision.transition(candidate.transitionType()),
                control, pending.getInboundIdempotencyHmac(), notice,
                Objects.requireNonNull(domainMutation, "domainMutation"));
        pending.accept(Instant.now(clock));
        pendingTransitions.save(pending);
        return reply;
    }

    private PendingFocusCandidate candidate(FocusDecision decision, FocusControl control) {
        if (!(decision instanceof FocusDecision.Transition transition)) {
            throw new IllegalArgumentException("pending focus transition requires a typed transition decision");
        }
        return switch (transition.type()) {
            case ENTER -> {
                FocusControl.EnterWorkflow enter = require(control, FocusControl.EnterWorkflow.class);
                yield new PendingFocusCandidate(FocusTransitionType.ENTER, enter.domain(), enter.workflowId(),
                        enter.safeLabel());
            }
            case SWITCH -> {
                FocusControl.SwitchWorkflow switchWorkflow = require(control, FocusControl.SwitchWorkflow.class);
                yield new PendingFocusCandidate(FocusTransitionType.SWITCH, switchWorkflow.domain(),
                        switchWorkflow.workflowId(), switchWorkflow.safeLabel());
            }
            default -> throw new IllegalArgumentException("pending focus transition supports workflow enter or switch only");
        };
    }

    private FocusTransitionNotice notice(WorkspaceContext context, ConversationScopeKey scope,
                                         PendingFocusTransition pending,
                                         PendingFocusCandidate candidate) {
        if (candidate.transitionType() == FocusTransitionType.ENTER) {
            return FocusTransitionNotice.forTransition(FocusTransitionType.ENTER, null,
                    candidate.safeLabel(), null);
        }
        ConversationFocus previous = active(context, scope);
        if (previous == null || !previous.getId().equals(pending.getFromFocusId())) {
            throw new PendingFocusTransitionExpiredException();
        }
        return FocusTransitionNotice.forTransition(FocusTransitionType.SWITCH, previous.getSafeLabel(),
                candidate.safeLabel(), null);
    }

    private FocusControl control(PendingFocusCandidate candidate) {
        return switch (candidate.transitionType()) {
            case ENTER -> new FocusControl.EnterWorkflow(candidate.rootDomain(), candidate.workflowId(),
                    candidate.safeLabel());
            case SWITCH -> new FocusControl.SwitchWorkflow(candidate.rootDomain(), candidate.workflowId(),
                    candidate.safeLabel());
            default -> throw new IllegalStateException("pending focus candidate transition is unavailable");
        };
    }

    private ConversationFocus active(WorkspaceContext context, ConversationScopeKey scope) {
        return focuses.findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationFocusStatus.ACTIVE)
                .orElse(null);
    }

    private ConversationFocusHead head(WorkspaceContext context, ConversationScopeKey scope) {
        return heads.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigest(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest())
                .orElseGet(() -> heads.save(ConversationFocusHead.create(scope, context.channel(),
                        Instant.now(clock))));
    }

    private static <T extends FocusControl> T require(FocusControl control, Class<T> expected) {
        if (!expected.isInstance(control)) {
            throw new IllegalArgumentException("focus control does not match pending transition");
        }
        return expected.cast(control);
    }
}
