package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintains one actor/workspace/scope-bound typed question without retaining user text or slots. */
@Service
@Transactional
public class ConversationPendingQuestionService {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final ConversationScopeResolver resolver;
    private final ConversationFocusService focuses;
    private final ConversationPendingQuestionRepository questions;
    private final Clock clock;

    public ConversationPendingQuestionService(ConversationScopeResolver resolver,
                                              ConversationFocusService focuses,
                                              ConversationPendingQuestionRepository questions,
                                              Clock clock) {
        this.resolver = resolver;
        this.focuses = focuses;
        this.questions = questions;
        this.clock = clock;
    }

    public ConversationPendingQuestion record(String questionCode, String inboundHmac) {
        ConversationFocus focus = focuses.activeFocus()
                .orElseThrow(() -> new IllegalStateException("active conversation focus is required"));
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        Optional<ConversationPendingQuestion> existing = questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING);
        if (existing.isPresent()) {
            ConversationPendingQuestion pending = existing.get();
            if (pending.expireIfDue(now)) {
                questions.save(pending);
                return createPending(scope, context, focus, questionCode, inboundHmac, now);
            }
            if (!pending.getWorkflowId().equals(focus.getWorkflowId())) {
                throw new IllegalStateException("another typed question is already pending in this scope");
            }
            pending.ask(questionCode, inboundHmac, now);
            return questions.save(pending);
        }
        return createPending(scope, context, focus, questionCode, inboundHmac, now);
    }

    public Optional<ConversationPendingQuestion> current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        return questions.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING)
                .filter(pending -> {
                    if (!pending.expireIfDue(now)) {
                        return true;
                    }
                    questions.save(pending);
                    return false;
                });
    }

    public boolean answerCurrent(String expectedQuestionCode) {
        return withCurrentLocked(pending -> {
            requireQuestion(pending, expectedQuestionCode);
            pending.answer(Instant.now(clock));
            questions.save(pending);
            return true;
        });
    }

    public Optional<ConversationPendingQuestion> beginContextChoice(String inboundHmac,
                                                                      UUID deferredWorkflowId) {
        return mutateCurrent(pending -> pending.beginContextChoice(inboundHmac, deferredWorkflowId,
                Instant.now(clock)));
    }

    public Optional<ConversationPendingQuestion> requestNewOperationContent(String inboundHmac) {
        return mutateCurrent(pending -> pending.requestNewOperationContent(inboundHmac, Instant.now(clock)));
    }

    public Optional<ConversationPendingQuestion> resumeInterruptedQuestion(String inboundHmac) {
        return mutateCurrent(pending -> pending.resumeInterruptedQuestion(inboundHmac, Instant.now(clock)));
    }

    public boolean completeDeferredNewOperation(UUID deferredWorkflowId, String inboundHmac) {
        return withCurrentLocked(pending -> pending.completeDeferredNewOperation(
                deferredWorkflowId, inboundHmac, Instant.now(clock)));
    }

    public boolean completeNewOperationContent(String inboundHmac) {
        return withCurrentLocked(pending -> pending.completeNewOperationContent(inboundHmac, Instant.now(clock)));
    }

    /** Returns false for replay or a missing/foreign pending operation, without revealing either. */
    public boolean cancelCurrent(UUID expectedWorkflowId, String inboundHmac) {
        return withCurrentLocked(pending -> {
            if (!pending.getWorkflowId().equals(expectedWorkflowId)) {
                return false;
            }
            return pending.cancel(inboundHmac, Instant.now(clock));
        });
    }

    private Optional<ConversationPendingQuestion> mutateCurrent(PendingMutation mutation) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        return questions.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING)
                .map(pending -> {
                    mutation.apply(pending);
                    return questions.save(pending);
                });
    }

    private boolean withCurrentLocked(PendingDecision decision) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        return questions.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING)
                .map(pending -> {
                    boolean changed = decision.apply(pending);
                    if (changed) {
                        questions.save(pending);
                    }
                    return changed;
                }).orElse(false);
    }

    private static void requireQuestion(ConversationPendingQuestion pending, String expectedQuestionCode) {
        if (!pending.getQuestionCode().equals(expectedQuestionCode)) {
            throw new IllegalStateException("pending question does not match expected lifecycle step");
        }
    }

    private ConversationPendingQuestion createPending(ConversationScopeKey scope,
                                                       WorkspaceContext context,
                                                       ConversationFocus focus,
                                                       String questionCode,
                                                       String inboundHmac,
                                                       Instant now) {
        if (focus.getWorkflowId() == null) {
            throw new IllegalStateException("typed workflow focus is required for a pending question");
        }
        ConversationPendingQuestion pending = ConversationPendingQuestion.pending(scope, context.channel(),
                focus.getId(), focus.getRootDomain(), focus.getWorkflowId(), questionCode,
                focus.getSafeLabel(), inboundHmac, now.plus(RETENTION), now);
        return questions.save(pending);
    }

    @FunctionalInterface
    private interface PendingMutation {
        boolean apply(ConversationPendingQuestion pending);
    }

    @FunctionalInterface
    private interface PendingDecision {
        boolean apply(ConversationPendingQuestion pending);
    }
}
