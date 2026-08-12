package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationCapability;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ScheduleClarificationDraftRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleClarificationDraftService {
    private static final Duration RETENTION = Duration.ofDays(7);
    private final ConversationScopeResolver resolver;
    private final ScheduleClarificationDraftRepository repository;
    private final Clock clock;
    private final ConversationPendingQuestionRepository pendingQuestions;

    public ScheduleClarificationDraftService(ConversationScopeResolver resolver,
                                             ScheduleClarificationDraftRepository repository,
                                             ConversationPendingQuestionRepository pendingQuestions,
                                             Clock clock) {
        this.resolver = resolver;
        this.repository = repository;
        this.pendingQuestions = pendingQuestions;
        this.clock = clock;
    }

    @Transactional
    public ScheduleClarificationDraft recurrence(
            String title, DayOfWeek weekday, LocalTime startTime, boolean periodExplicit,
            Integer durationMinutes, LocalDate until, boolean recurrenceExplicit,
            String holidayPolicy, String closurePolicy, String jurisdiction) {
        return merge(ScheduleClarificationCapability.CONDITIONAL_RECURRENCE,
                draft -> draft.mergeRecurrence(title, weekday, startTime, periodExplicit,
                        durationMinutes, until, recurrenceExplicit, holidayPolicy,
                        closurePolicy, jurisdiction, Instant.now(clock)));
    }

    @Transactional
    public ScheduleClarificationDraft monthly(
            Integer ordinal, Integer monthOffset, DayOfWeek weekday, LocalTime startTime, boolean periodExplicit,
            Integer durationMinutes, String title) {
        return merge(ScheduleClarificationCapability.MONTHLY_ORDINAL,
                draft -> draft.mergeMonthly(ordinal, monthOffset, weekday, startTime, periodExplicit,
                        durationMinutes, title, Instant.now(clock)));
    }

    @Transactional
    public ScheduleClarificationDraft venue(
            Instant eventAt, Integer durationMinutes, String title,
            String primaryPlace, String fallbackPlace, Instant decisionAt,
            boolean decisionPeriodExplicit) {
        return merge(ScheduleClarificationCapability.CONDITIONAL_VENUE,
                draft -> draft.mergeVenue(eventAt, durationMinutes, title, primaryPlace,
                        fallbackPlace, decisionAt, decisionPeriodExplicit, Instant.now(clock)));
    }

    @Transactional
    public void complete(ScheduleClarificationCapability capability) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        ScheduleClarificationDraft draft = current(capability);
        Instant now = Instant.now(clock);
        draft.complete(now);
        pendingQuestions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndWorkflowIdAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        draft.getId(), ConversationPendingQuestionStatus.PENDING)
                .ifPresent(question -> question.answer(now));
    }

    @Transactional
    public Optional<ScheduleClarificationDraft> findCurrent(
            ScheduleClarificationCapability capability) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Optional<ScheduleClarificationDraft> existing = repository
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndCapabilityAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        capability, ScheduleClarificationDraftStatus.PENDING);
        if (existing.isPresent() && existing.orElseThrow().expireIfDue(Instant.now(clock))) {
            repository.save(existing.orElseThrow());
            return Optional.empty();
        }
        return existing;
    }

    /**
     * A slot-only follow-up may advance only the workflow selected by the durable pointer. If the
     * pointer has expired, exactly one remaining draft is an unambiguous bounded fallback.
     */
    @Transactional
    public boolean mayConsumeContinuation(ScheduleClarificationCapability capability) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Optional<ScheduleClarificationDraft> candidate = findCurrent(capability);
        if (candidate.isEmpty()) return false;
        java.util.UUID quotedDraftId = TrustedConversationReferenceContext.currentDraftId();
        if (quotedDraftId != null) {
            return candidate.orElseThrow().getId().equals(quotedDraftId);
        }
        Optional<com.aproject.aidriven.mymobilesecretary.conversation.domain
                .ConversationPendingQuestion> selected = pendingQuestions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING);
        if (selected.isPresent()) {
            return selected.orElseThrow().getWorkflowId().equals(candidate.orElseThrow().getId());
        }
        return repository
                .findAllByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatusOrderByCreatedAtAsc(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ScheduleClarificationDraftStatus.PENDING)
                .stream()
                .filter(draft -> !draft.expireIfDue(Instant.now(clock)))
                .count() == 1;
    }

    private ScheduleClarificationDraft merge(
            ScheduleClarificationCapability capability,
            Consumer<ScheduleClarificationDraft> mutation) {
        ScheduleClarificationDraft draft = currentOrCreate(capability);
        mutation.accept(draft);
        return repository.save(draft);
    }

    private ScheduleClarificationDraft current(ScheduleClarificationCapability capability) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        return repository
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndCapabilityAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        capability, ScheduleClarificationDraftStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("schedule clarification draft is unavailable"));
    }

    private ScheduleClarificationDraft currentOrCreate(ScheduleClarificationCapability capability) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        var existing = repository
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndCapabilityAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        capability, ScheduleClarificationDraftStatus.PENDING);
        if (existing.isPresent() && !existing.orElseThrow().expireIfDue(now)) {
            return existing.orElseThrow();
        }
        existing.ifPresent(repository::saveAndFlush);
        return repository.save(ScheduleClarificationDraft.create(scope, context.channel(),
                capability, now.plus(RETENTION), now));
    }
}
