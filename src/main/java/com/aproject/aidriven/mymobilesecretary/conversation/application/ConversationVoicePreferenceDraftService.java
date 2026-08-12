package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceTarget;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationVoicePreferenceDraftRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists typed voice-preference answers while preserving an interrupted business question. */
@Service
public class ConversationVoicePreferenceDraftService {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final ConversationScopeResolver resolver;
    private final ConversationVoicePreferenceDraftRepository drafts;
    private final ConversationPendingQuestionRepository questions;
    private final ConversationVoiceProfileService profiles;
    private final Clock clock;

    public ConversationVoicePreferenceDraftService(
            ConversationScopeResolver resolver,
            ConversationVoicePreferenceDraftRepository drafts,
            ConversationPendingQuestionRepository questions,
            ConversationVoiceProfileService profiles,
            Clock clock) {
        this.resolver = resolver;
        this.drafts = drafts;
        this.questions = questions;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional
    public ConversationVoicePreferenceDraft start() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        Optional<ConversationVoicePreferenceDraft> current = findCurrent(context, scope);
        if (current.isPresent() && !current.orElseThrow().expireIfDue(now)) {
            return current.orElseThrow();
        }
        current.ifPresent(drafts::saveAndFlush);

        UUID suspendedId = questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING)
                .map(question -> suspend(question, now))
                .orElse(null);
        ConversationVoicePreferenceDraft draft = ConversationVoicePreferenceDraft.start(
                scope, context.channel(), suspendedId, now.plus(RETENTION), now);
        return drafts.saveAndFlush(draft);
    }

    @Transactional
    public Optional<ConversationVoicePreferenceDraft> current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Optional<ConversationVoicePreferenceDraft> current = findCurrent(context, scope);
        if (current.isPresent() && current.orElseThrow().expireIfDue(Instant.now(clock))) {
            ConversationVoicePreferenceDraft expired = drafts.saveAndFlush(current.orElseThrow());
            restoreSuspended(expired);
            return Optional.empty();
        }
        return current;
    }

    @Transactional
    public ConversationVoicePreferenceDraft choose(
            ConversationVoicePreferenceDraft draft, ConversationVoicePreferenceTarget target) {
        draft.choose(target, Instant.now(clock));
        return drafts.saveAndFlush(draft);
    }

    @Transactional
    public ConversationVoicePreferenceDraft answerAssistant(
            ConversationVoicePreferenceDraft draft, String value) {
        draft.answerAssistantName(value, Instant.now(clock));
        return drafts.saveAndFlush(draft);
    }

    @Transactional
    public ConversationVoicePreferenceDraft answerUserAddress(
            ConversationVoicePreferenceDraft draft, String value) {
        draft.answerUserAddress(value, Instant.now(clock));
        return drafts.saveAndFlush(draft);
    }

    @Transactional
    public ConversationVoiceProfileService.Settings applyAndComplete(
            ConversationVoicePreferenceDraft draft) {
        if (!draft.completeIfReady(Instant.now(clock))) {
            throw new IllegalStateException("voice preference draft is incomplete");
        }
        ConversationVoiceProfileService.Settings settings = switch (draft.getTarget()) {
            case ASSISTANT_NAME -> profiles.setAssistantName(draft.getAssistantSelfName());
            case USER_ADDRESS -> profiles.setUserAddress(draft.getUserAddress());
            case BOTH -> profiles.setBoth(draft.getAssistantSelfName(), draft.getUserAddress());
            case UNDECIDED -> throw new IllegalStateException("voice preference target is undecided");
        };
        drafts.saveAndFlush(draft);
        finishVoiceQuestionAndRestore(draft);
        return settings;
    }

    @Transactional
    public ConversationVoiceProfileService.Settings answerBothAndComplete(
            ConversationVoicePreferenceDraft draft, String assistantName, String userAddress) {
        draft.answerBoth(assistantName, userAddress, Instant.now(clock));
        return applyAndComplete(draft);
    }

    @Transactional
    public void cancel(ConversationVoicePreferenceDraft draft) {
        draft.cancel(Instant.now(clock));
        drafts.saveAndFlush(draft);
        finishVoiceQuestionAndRestore(draft);
    }

    private Optional<ConversationVoicePreferenceDraft> findCurrent(
            WorkspaceContext context, ConversationScopeKey scope) {
        return drafts
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationVoicePreferenceDraftStatus.PENDING);
    }

    private UUID suspend(ConversationPendingQuestion question, Instant now) {
        question.suspend(now);
        return questions.saveAndFlush(question).getId();
    }

    private void finishVoiceQuestionAndRestore(ConversationVoicePreferenceDraft draft) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING)
                .filter(question -> question.getWorkflowId().equals(draft.getId()))
                .ifPresent(question -> {
                    question.answer(Instant.now(clock));
                    questions.saveAndFlush(question);
                });
        restoreSuspended(draft);
    }

    private void restoreSuspended(ConversationVoicePreferenceDraft draft) {
        if (draft.getSuspendedQuestionId() == null) {
            return;
        }
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        questions.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        draft.getSuspendedQuestionId(), context.workspaceId(), context.actorId())
                .filter(question -> question.getStatus() == ConversationPendingQuestionStatus.SUSPENDED)
                .ifPresent(question -> {
                    question.resume(Instant.now(clock));
                    questions.saveAndFlush(question);
                });
    }
}
