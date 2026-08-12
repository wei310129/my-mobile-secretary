package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RestaurantBookingDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.RestaurantBookingDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.RestaurantBookingDraftRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Actor-owned typed state for restaurant information guidance; never executes a booking. */
@Service
public class RestaurantBookingDraftService {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final ConversationScopeResolver resolver;
    private final RestaurantBookingDraftRepository drafts;
    private final ConversationPendingQuestionRepository questions;
    private final Clock clock;

    public RestaurantBookingDraftService(
            ConversationScopeResolver resolver,
            RestaurantBookingDraftRepository drafts,
            ConversationPendingQuestionRepository questions,
            Clock clock) {
        this.resolver = resolver;
        this.drafts = drafts;
        this.questions = questions;
        this.clock = clock;
    }

    @Transactional
    public RestaurantBookingDraft merge(
            String restaurant, Instant diningAt, Integer partySize,
            boolean includesChild, boolean requiresAccessibility, boolean includesPet) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        Optional<RestaurantBookingDraft> found = currentLocked(context, scope);
        RestaurantBookingDraft draft;
        if (found.isPresent() && !found.orElseThrow().expireIfDue(now)) {
            draft = found.orElseThrow();
        } else {
            found.ifPresent(expired -> {
                drafts.saveAndFlush(expired);
                finishQuestion(context, scope, expired, now);
                restoreSuspended(context, expired, now);
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
            draft = RestaurantBookingDraft.start(
                    scope, context.channel(), suspendedId, now.plus(RETENTION), now);
        }
        draft.merge(restaurant, diningAt, partySize,
                includesChild, requiresAccessibility, includesPet, now);
        return drafts.save(draft);
    }

    @Transactional
    public Optional<RestaurantBookingDraft> current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Optional<RestaurantBookingDraft> current = currentLocked(context, scope);
        Instant now = Instant.now(clock);
        if (current.isPresent() && current.orElseThrow().expireIfDue(now)) {
            RestaurantBookingDraft expired = drafts.saveAndFlush(current.orElseThrow());
            finishQuestion(context, scope, expired, now);
            restoreSuspended(context, expired, now);
            return Optional.empty();
        }
        return current;
    }

    @Transactional
    public void complete(UUID id) {
        finish(id, true);
    }

    @Transactional
    public void cancel(UUID id) {
        finish(id, false);
    }

    private void finish(UUID id, boolean completed) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        RestaurantBookingDraft draft = drafts.findById(id).orElseThrow();
        Instant now = Instant.now(clock);
        if (completed) draft.complete(now); else draft.cancel(now);
        drafts.saveAndFlush(draft);
        finishQuestion(context, scope, draft, now);
        restoreSuspended(context, draft, now);
    }

    private Optional<RestaurantBookingDraft> currentLocked(
            WorkspaceContext context, ConversationScopeKey scope) {
        return drafts
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        RestaurantBookingDraftStatus.PENDING);
    }

    private void finishQuestion(
            WorkspaceContext context, ConversationScopeKey scope,
            RestaurantBookingDraft draft, Instant now) {
        questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndWorkflowIdAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        draft.getId(), ConversationPendingQuestionStatus.PENDING)
                .ifPresent(question -> question.answer(now));
    }

    private void restoreSuspended(
            WorkspaceContext context, RestaurantBookingDraft draft, Instant now) {
        if (draft.getSuspendedQuestionId() == null) return;
        questions.findWithLockByIdAndWorkspaceIdAndCreatedByUserId(
                        draft.getSuspendedQuestionId(), context.workspaceId(), context.actorId())
                .filter(question -> question.getStatus() == ConversationPendingQuestionStatus.SUSPENDED)
                .ifPresent(question -> {
                    question.resume(now);
                    question.expireIfDue(now);
                    questions.saveAndFlush(question);
                });
    }
}
