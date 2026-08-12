package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestionStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationRepairDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.PublicPlaceLookupDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationCapability;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationFocusRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationPendingQuestionRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationRepairDraftRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ConversationVoicePreferenceDraftRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.PublicPlaceLookupDraftRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.RestaurantBookingDraftRepository;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ScheduleClarificationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationReply;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Maintains one actor/scope-bound typed question without retaining prompts or user answers. */
@Service
public class ConversationPendingQuestionService {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final ConversationScopeResolver resolver;
    private final ConversationFocusRepository focuses;
    private final ConversationPendingQuestionRepository questions;
    private final ScheduleClarificationDraftRepository scheduleDrafts;
    private final ConversationRepairDraftRepository repairDrafts;
    private final PublicPlaceLookupDraftRepository publicPlaceDrafts;
    private final Clock clock;
    private CalendarIntentDraftService calendarIntentDraftService;
    private ConversationVoicePreferenceDraftRepository voicePreferenceDrafts;
    private RestaurantBookingDraftRepository restaurantBookingDrafts;
    private com.aproject.aidriven.mymobilesecretary.conversation.persistence
                    .RoutePlaceCreationDraftRepository
            routePlaceCreationDrafts;

    public ConversationPendingQuestionService(
            ConversationScopeResolver resolver, ConversationFocusRepository focuses,
            ConversationPendingQuestionRepository questions,
            ScheduleClarificationDraftRepository scheduleDrafts,
            ConversationRepairDraftRepository repairDrafts, Clock clock) {
        this(resolver, focuses, questions, scheduleDrafts, repairDrafts, null, clock);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationPendingQuestionService(
            ConversationScopeResolver resolver, ConversationFocusRepository focuses,
            ConversationPendingQuestionRepository questions,
            ScheduleClarificationDraftRepository scheduleDrafts,
            ConversationRepairDraftRepository repairDrafts,
            PublicPlaceLookupDraftRepository publicPlaceDrafts, Clock clock) {
        this.resolver = resolver;
        this.focuses = focuses;
        this.questions = questions;
        this.scheduleDrafts = scheduleDrafts;
        this.repairDrafts = repairDrafts;
        this.publicPlaceDrafts = publicPlaceDrafts;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCalendarIntentDraftService(CalendarIntentDraftService service) {
        this.calendarIntentDraftService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVoicePreferenceDrafts(ConversationVoicePreferenceDraftRepository repository) {
        this.voicePreferenceDrafts = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRestaurantBookingDrafts(RestaurantBookingDraftRepository repository) {
        this.restaurantBookingDrafts = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRoutePlaceCreationDrafts(
            com.aproject.aidriven.mymobilesecretary.conversation.persistence
                            .RoutePlaceCreationDraftRepository
                    repository) {
        this.routePlaceCreationDrafts = repository;
    }

    @Transactional
    public ConversationPendingQuestion record(
            PublicConversationReply.NextQuestion nextQuestion, String inboundHmac) {
        return record(nextQuestion, null, inboundHmac);
    }

    @Transactional
    public ConversationPendingQuestion record(
            PublicConversationReply.NextQuestion nextQuestion,
            ConversationFocusBinding trustedBinding,
            String inboundHmac) {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        Optional<UUID> typedWorkflow = Optional.ofNullable(trustedBinding)
                .map(ConversationFocusBinding::workflowId)
                .or(() -> typedWorkflow(context, scope, nextQuestion.code()));
        Optional<ConversationPendingQuestion> existing = questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationPendingQuestionStatus.PENDING);
        if (existing.isPresent()) {
            ConversationPendingQuestion pending = existing.orElseThrow();
            if (!pending.expireIfDue(now)) {
                if (isGenericUnknown(nextQuestion.code())
                        && !isGenericUnknown(pending.getQuestionCode())) {
                    return pending;
                }
                if (typedWorkflow.isEmpty()
                        || pending.getWorkflowId().equals(typedWorkflow.orElseThrow())) {
                    pending.ask(nextQuestion.code(), inboundHmac, now);
                    return questions.save(pending);
                }
                // An explicit new typed intake moves the single active pointer atomically while
                // retaining the earlier domain draft. Never attach a new question to an old UUID.
                pending.expire(now);
            }
            questions.saveAndFlush(pending);
        }
        ConversationFocus active = focuses
                .findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ConversationFocusStatus.ACTIVE)
                .orElse(null);
        boolean activeOwnsWorkflow = active != null
                && typedWorkflow.isPresent()
                && typedWorkflow.orElseThrow().equals(active.getWorkflowId());
        boolean activeMayOwnQuestion = active != null
                && (typedWorkflow.isPresent()
                        ? activeOwnsWorkflow
                        : !nextQuestion.code().startsWith("route."));
        boolean trustedBindingMatchesActive = active != null
                && trustedBinding != null
                && active.getRootDomain().equalsIgnoreCase(trustedBinding.domain())
                && (trustedBinding.workflowId() != null
                        ? trustedBinding.workflowId().equals(active.getWorkflowId())
                        : trustedBinding.routingKey().equals(active.getRoutingKey()));
        String rootDomain = trustedBinding != null
                ? normalizedDomain(trustedBinding.domain())
                : activeMayOwnQuestion
                        ? normalizedDomain(active.getRootDomain())
                        : rootDomain(nextQuestion.code());
        UUID workflowId = typedWorkflow
                .orElseGet(() -> activeMayOwnQuestion && active.getWorkflowId() != null
                        ? active.getWorkflowId() : UUID.randomUUID());
        UUID focusId = trustedBinding == null
                ? activeMayOwnQuestion
                        ? active.getId()
                        : null
                : trustedBindingMatchesActive ? active.getId() : null;
        String workflowSafeLabel = trustedBinding != null
                ? trustedBinding.safeLabel()
                : activeMayOwnQuestion
                        ? active.getSafeLabel()
                        : null;
        return questions.save(ConversationPendingQuestion.pending(scope, context.channel(), focusId,
                rootDomain, workflowId, nextQuestion.code(), workflowSafeLabel, inboundHmac,
                now.plus(RETENTION), now));
    }

    private static boolean isGenericUnknown(String questionCode) {
        return questionCode != null && questionCode.startsWith("intent.unknown-");
    }

    @Transactional
    public Optional<ConversationPendingQuestion> current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Optional<ConversationPendingQuestion> current = questions
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                ConversationPendingQuestionStatus.PENDING);
        if (current.isPresent() && current.orElseThrow().expireIfDue(Instant.now(clock))) {
            questions.save(current.orElseThrow());
            return Optional.empty();
        }
        return current;
    }

    @Transactional
    public void finishCurrentUnknownRecovery() {
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()
                || !pending.orElseThrow().getQuestionCode().startsWith("intent.unknown-")) {
            return;
        }
        ConversationPendingQuestion question = pending.orElseThrow();
        question.answer(Instant.now(clock));
        questions.save(question);
    }

    @Transactional
    public boolean answerCurrent(String expectedQuestionCode) {
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()
                || !pending.orElseThrow().getQuestionCode().equals(expectedQuestionCode)) {
            return false;
        }
        ConversationPendingQuestion question = pending.orElseThrow();
        question.answer(Instant.now(clock));
        questions.save(question);
        return true;
    }

    @Transactional
    public Optional<ConversationPendingQuestion> beginContextChoice(String inboundHmac) {
        return beginContextChoice(inboundHmac, null);
    }

    @Transactional
    public Optional<ConversationPendingQuestion> beginContextChoice(
            String inboundHmac, UUID deferredWorkflowId) {
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()) return Optional.empty();
        ConversationPendingQuestion question = pending.orElseThrow();
        question.beginContextChoice(inboundHmac, deferredWorkflowId, Instant.now(clock));
        return Optional.of(questions.save(question));
    }

    @Transactional
    public boolean completeDeferredNewOperation(
            UUID expectedDeferredWorkflowId, String inboundHmac) {
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()) return false;
        ConversationPendingQuestion question = pending.orElseThrow();
        boolean changed = question.completeDeferredNewOperation(
                expectedDeferredWorkflowId, inboundHmac, Instant.now(clock));
        questions.save(question);
        return changed;
    }

    @Transactional
    public Optional<ConversationPendingQuestion> requestNewOperationContent(String inboundHmac) {
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()) return Optional.empty();
        ConversationPendingQuestion question = pending.orElseThrow();
        question.requestNewOperationContent(inboundHmac, Instant.now(clock));
        return Optional.of(questions.save(question));
    }

    @Transactional
    public Optional<ConversationPendingQuestion> resumeInterruptedQuestion(String inboundHmac) {
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()) return Optional.empty();
        ConversationPendingQuestion question = pending.orElseThrow();
        question.resumeInterruptedQuestion(inboundHmac, Instant.now(clock));
        return Optional.of(questions.save(question));
    }

    @Transactional
    public boolean completeNewOperationContent(String inboundHmac) {
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()
                || !pending.orElseThrow().getQuestionCode()
                        .equals("conversation.new-operation-content")) {
            return false;
        }
        ConversationPendingQuestion question = pending.orElseThrow();
        boolean changed = question.completeNewOperationContent(inboundHmac, Instant.now(clock));
        questions.save(question);
        return changed;
    }

    @Transactional
    public boolean cancelCurrent(UUID expectedWorkflowId, String inboundHmac) {
        if (expectedWorkflowId == null) return false;
        Optional<ConversationPendingQuestion> pending = current();
        if (pending.isEmpty()
                || !expectedWorkflowId.equals(pending.orElseThrow().getWorkflowId())) {
            return false;
        }
        ConversationPendingQuestion question = pending.orElseThrow();
        boolean changed = question.cancel(inboundHmac, Instant.now(clock));
        questions.saveAndFlush(question);
        return changed;
    }

    private static String rootDomain(String questionCode) {
        String prefix = questionCode.split("[.]", 2)[0]
                .toLowerCase(Locale.ROOT).replace('-', '_');
        return prefix.length() <= 60 ? prefix : prefix.substring(0, 60);
    }

    private static String normalizedDomain(String domain) {
        String normalized = domain.toLowerCase(Locale.ROOT).replace('-', '_');
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 60);
    }

    private Optional<UUID> typedWorkflow(
            WorkspaceContext context, ConversationScopeKey scope, String questionCode) {
        if (questionCode.startsWith("voice.") && voicePreferenceDrafts != null) {
            return voicePreferenceDrafts
                    .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                            context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                            com.aproject.aidriven.mymobilesecretary.conversation.domain
                                    .ConversationVoicePreferenceDraftStatus.PENDING)
                    .map(com.aproject.aidriven.mymobilesecretary.conversation.domain
                            .ConversationVoicePreferenceDraft::getId);
        }
        if (questionCode.startsWith("conversation-repair.")) {
            return repairDrafts
                    .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                            context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                            ConversationRepairDraftStatus.PENDING)
                    .map(com.aproject.aidriven.mymobilesecretary.conversation.domain
                            .ConversationRepairDraft::getId);
        }
        if (questionCode.startsWith("place.system-") && publicPlaceDrafts != null) {
            return publicPlaceDrafts
                    .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                            context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                            PublicPlaceLookupDraftStatus.PENDING)
                    .map(com.aproject.aidriven.mymobilesecretary.conversation.domain
                            .PublicPlaceLookupDraft::getId);
        }
        if (questionCode.startsWith("place.route-") && routePlaceCreationDrafts != null) {
            return routePlaceCreationDrafts
                    .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                            context.workspaceId(),
                            context.actorId(),
                            context.channel(),
                            scope.digest(),
                            com.aproject.aidriven.mymobilesecretary.conversation.domain
                                    .RoutePlaceCreationDraftStatus.PENDING)
                    .map(com.aproject.aidriven.mymobilesecretary.conversation.domain
                            .RoutePlaceCreationDraft::getId);
        }
        if (questionCode.startsWith("booking.") && restaurantBookingDrafts != null) {
            return restaurantBookingDrafts
                    .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                            context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                            com.aproject.aidriven.mymobilesecretary.conversation.domain
                                    .RestaurantBookingDraftStatus.PENDING)
                    .map(com.aproject.aidriven.mymobilesecretary.conversation.domain
                            .RestaurantBookingDraft::getId);
        }
        if (questionCode.startsWith("route.") && calendarIntentDraftService != null) {
            if (questionCode.equals("route.new-request")) {
                return Optional.empty();
            }
            Optional<UUID> activeCalendarWorkflow = focuses
                    .findByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatus(
                            context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                            ConversationFocusStatus.ACTIVE)
                    .filter(focus -> "CALENDAR_DRAFT".equalsIgnoreCase(focus.getRootDomain()))
                    .map(ConversationFocus::getWorkflowId)
                    .filter(java.util.Objects::nonNull);
            if (activeCalendarWorkflow.isPresent()) return activeCalendarWorkflow;
            return calendarIntentDraftService.currentTransportConversation()
                    .map(CalendarIntentDraftService.DraftView::id);
        }
        ScheduleClarificationCapability capability = questionCode.startsWith("conditional-recurrence.")
                ? ScheduleClarificationCapability.CONDITIONAL_RECURRENCE
                : questionCode.startsWith("conditional-venue.")
                        ? ScheduleClarificationCapability.CONDITIONAL_VENUE
                        : questionCode.startsWith("monthly-ordinal.")
                                ? ScheduleClarificationCapability.MONTHLY_ORDINAL : null;
        if (capability == null) return Optional.empty();
        return scheduleDrafts
                .findWithLockByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndCapabilityAndStatus(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        capability, ScheduleClarificationDraftStatus.PENDING)
                .map(com.aproject.aidriven.mymobilesecretary.conversation.domain
                        .ScheduleClarificationDraft::getId);
    }
}
