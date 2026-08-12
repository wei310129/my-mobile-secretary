package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairAspect;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairKind;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairPriorAction;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairScope;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationRepairDraftRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationRepairType;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists the minimum typed state needed to resume one feedback repair across restart. */
@Service
public class ConversationRepairDraftService {

    private static final Duration RETENTION = Duration.ofDays(7);
    private final ConversationScopeResolver resolver;
    private final ConversationRepairDraftRepository drafts;
    private final ConversationPendingQuestionRepository questions;
    private final Clock clock;

    public ConversationRepairDraftService(
            ConversationScopeResolver resolver, ConversationRepairDraftRepository drafts,
            ConversationPendingQuestionRepository questions, Clock clock) {
        this.resolver = resolver;
        this.drafts = drafts;
        this.questions = questions;
        this.clock = clock;
    }

    @Transactional
    public ConversationRepairDraft start(
            ConversationRepairType type, ConversationSnapshot snapshot) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        drafts.findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationRepairDraftStatus.PENDING)
                .ifPresent(existing -> {
                    if (!existing.expireIfDue(now)) existing.cancel(now);
                    drafts.saveAndFlush(existing);
                    finishRepairQuestion(context, scope, existing, now);
                    restoreSuspended(context, existing, now);
                });
        UUID suspendedId = questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING)
                .map(question -> {
                    question.suspend(now);
                    return questions.saveAndFlush(question).getId();
                })
                .orElse(null);
        ConversationRepairDraft draft = ConversationRepairDraft.create(
                scope, context.channel(), kind(type), priorAction(snapshot.lastAction()),
                timeScope(snapshot.lastUserText()), suspendedId, now.plus(RETENTION), now);
        return drafts.save(draft);
    }

    @Transactional
    public Optional<ConversationRepairDraft> current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Optional<ConversationRepairDraft> current = drafts
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationRepairDraftStatus.PENDING);
        if (current.isPresent() && current.orElseThrow().expireIfDue(Instant.now(clock))) {
            ConversationRepairDraft expired = drafts.saveAndFlush(current.orElseThrow());
            Instant now = Instant.now(clock);
            finishRepairQuestion(context, scope, expired, now);
            restoreSuspended(context, expired, now);
            return Optional.empty();
        }
        return current;
    }

    @Transactional
    public void complete(UUID draftId) {
        finish(draftId, true);
    }

    @Transactional
    public void cancel(UUID draftId) {
        finish(draftId, false);
    }

    @Transactional
    public ConversationRepairDraft refine(UUID draftId, ConversationRepairAspect aspect) {
        ConversationRepairDraft draft = drafts.findById(draftId).orElseThrow();
        draft.refine(aspect, Instant.now(clock));
        return drafts.save(draft);
    }

    private void finish(UUID draftId, boolean completed) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        ConversationRepairDraft draft = drafts.findById(draftId).orElseThrow();
        Instant now = Instant.now(clock);
        if (completed) draft.complete(now); else draft.cancel(now);
        drafts.saveAndFlush(draft);
        finishRepairQuestion(context, scope, draft, now);
        restoreSuspended(context, draft, now);
    }

    private void finishRepairQuestion(
            WorkspaceContext context, ConversationScopeKey scope,
            ConversationRepairDraft draft, Instant now) {
        questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndWorkflowIdAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        draft.getId(), ConversationPendingQuestionStatus.PENDING)
                .ifPresent(question -> question.answer(now));
    }

    private void restoreSuspended(
            WorkspaceContext context, ConversationRepairDraft draft, Instant now) {
        if (draft.getSuspendedQuestionId() == null) {
            return;
        }
        questions.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        draft.getSuspendedQuestionId(), context.workspaceId(), context.actorId())
                .filter(question -> question.getStatus() == ConversationPendingQuestionStatus.SUSPENDED)
                .ifPresent(question -> {
                    question.resume(now);
                    question.expireIfDue(now);
                    questions.saveAndFlush(question);
                });
    }

    private static ConversationRepairKind kind(ConversationRepairType type) {
        return ConversationRepairKind.valueOf(type.name());
    }

    private static ConversationRepairPriorAction priorAction(String value) {
        if (value == null) return ConversationRepairPriorAction.OTHER;
        try {
            return ConversationRepairPriorAction.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return ConversationRepairPriorAction.OTHER;
        }
    }

    private static ConversationRepairScope timeScope(String text) {
        if (text == null) return ConversationRepairScope.UPCOMING;
        if (text.contains("明天") || text.contains("明日")) return ConversationRepairScope.TOMORROW;
        if (text.contains("這週") || text.contains("本週")) return ConversationRepairScope.WEEK;
        if (text.contains("今天") || text.contains("今日")) return ConversationRepairScope.TODAY;
        return ConversationRepairScope.UPCOMING;
    }
}
