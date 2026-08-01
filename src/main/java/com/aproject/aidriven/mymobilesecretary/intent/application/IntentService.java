package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusControlPhrasePolicy;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusDirective;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityShadowRouter;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTraceDraft;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 意圖編排:解析 → 驗證 → 執行。
 *
 * 可靠度鐵律:LLM 失敗時不得把查詢或修改指令誤存成任務。只有原文帶明確「提醒／記下」
 * 指示時才建立保底待辦；其餘原文由對話與意圖問題紀錄保存，並誠實告知沒有異動資料。
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private final ObjectProvider<IntentInterpreter> interpreterProvider;
    private final TaskService taskService;
    private final IntentIssueService issueService;
    private final IntentHandlerRegistry intentHandlerRegistry;
    private final DailyScheduleOverviewService dailyScheduleOverviewService;
    private final ReminderTimingAnswerService reminderTimingAnswerService;
    private final LastActivityAnswerService lastActivityAnswerService;
    private final ActivityCountAnswerService activityCountAnswerService;
    private final TravelPlanningIntakeService travelPlanningIntakeService;
    private final TravelPackingAnswerService travelPackingAnswerService;
    private final TravelItineraryDraftAnswerService travelItineraryDraftAnswerService;
    private final ScheduleTaskConflictAnswerService scheduleTaskConflictAnswerService;
    private final TaskDetailAnswerService taskDetailAnswerService;
    private final DelegatedDecisionService delegatedDecisionService;
    private final ConversationContextService conversationContextService;
    private final com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService placeAliasService;
    private final Clock clock;
    private IntentDecisionTraceService decisionTraceService;
    private CapabilityShadowRouter capabilityShadowRouter;
    private com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService
            familyMessageService;
    private com.aproject.aidriven.mymobilesecretary.family.application.FamilyPersonService
            familyPersonService;
    private ActivityMutationDisambiguationService activityMutationDisambiguationService;
    private SchedulePlaceBindingAnswerService schedulePlaceBindingAnswerService;
    private com.aproject.aidriven.mymobilesecretary.event.application.EventIntakeService
            eventIntakeService;
    private com.aproject.aidriven.mymobilesecretary.safety.application.WorkSchoolSuspensionService
            suspensionService;
    private LifestyleWindowConversationService lifestyleWindowConversationService;
    private LunarCalendarConversationService lunarCalendarConversationService;
    private ConditionalRecurrenceConversationService conditionalRecurrenceConversationService;
    private MonthlyOrdinalRecurrenceConversationService monthlyOrdinalRecurrenceConversationService;
    private FamilyTransportConversationService familyTransportConversationService;
    private SchoolTransportConversationService schoolTransportConversationService;
    private ScheduleCorrectionConversationService scheduleCorrectionConversationService;
    private BoundedFreeSlotConversationService boundedFreeSlotConversationService;
    private UncertainScheduleConditionConversationService uncertainScheduleConditionConversationService;
    private ConditionalVenueConversationService conditionalVenueConversationService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService
            universalLifeRecordService;
    private com.aproject.aidriven.mymobilesecretary.payment.application.BankTransferService
            bankTransferService;
    private com.aproject.aidriven.mymobilesecretary.payment.application.PaymentNoticeService
            paymentNoticeService;
    private com.aproject.aidriven.mymobilesecretary.draft.application.DraftRetentionConversationService
            draftRetentionService;
    private com.aproject.aidriven.mymobilesecretary.shared.time.TimeDisplayPreferenceService
            timeDisplayPreferenceService;
    private QuotedPlaceCorrectionConversationService quotedPlaceCorrectionService;
    private com.aproject.aidriven.mymobilesecretary.utility.application.UtilityBillService
            utilityBillService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.tag.application
                    .TaggedRecordConversationService
            taggedRecordConversationService;
    private PurchaseConversationService purchaseConversationService;
    private PlanningItemTypeAnswerService planningItemTypeAnswerService;
    private ScheduleSelectionConversationService scheduleSelectionConversationService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.application.KnowledgeRecordDeletionService
            knowledgeRecordDeletionService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.application.KnowledgeRecordEditingService
            knowledgeRecordEditingService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.application
                    .ProductDraftCompletionConversationService
            productDraftCompletionService;
    private com.aproject.aidriven.mymobilesecretary.venue.application.VenueVisitInformationService
            venueVisitInformationService;
    private com.aproject.aidriven.mymobilesecretary.conversation.application
                    .ConversationFocusIntentExecutor
            conversationFocusIntentExecutor;
    private com.aproject.aidriven.mymobilesecretary.conversation.application
                    .ConversationIntentReplayService
            conversationIntentReplayService;

    public IntentService(ObjectProvider<IntentInterpreter> interpreterProvider,
                         TaskService taskService,
                         IntentIssueService issueService,
                         IntentHandlerRegistry intentHandlerRegistry,
                         DailyScheduleOverviewService dailyScheduleOverviewService,
                         ReminderTimingAnswerService reminderTimingAnswerService,
                         LastActivityAnswerService lastActivityAnswerService,
                         ActivityCountAnswerService activityCountAnswerService,
                         TravelPlanningIntakeService travelPlanningIntakeService,
                         TravelPackingAnswerService travelPackingAnswerService,
                         TravelItineraryDraftAnswerService travelItineraryDraftAnswerService,
                         ScheduleTaskConflictAnswerService scheduleTaskConflictAnswerService,
                         TaskDetailAnswerService taskDetailAnswerService,
                         DelegatedDecisionService delegatedDecisionService,
                         ConversationContextService conversationContextService,
                         com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService placeAliasService,
                         Clock clock) {
        this.interpreterProvider = interpreterProvider;
        this.taskService = taskService;
        this.issueService = issueService;
        this.intentHandlerRegistry = intentHandlerRegistry;
        this.dailyScheduleOverviewService = dailyScheduleOverviewService;
        this.reminderTimingAnswerService = reminderTimingAnswerService;
        this.lastActivityAnswerService = lastActivityAnswerService;
        this.activityCountAnswerService = activityCountAnswerService;
        this.travelPlanningIntakeService = travelPlanningIntakeService;
        this.travelPackingAnswerService = travelPackingAnswerService;
        this.travelItineraryDraftAnswerService = travelItineraryDraftAnswerService;
        this.scheduleTaskConflictAnswerService = scheduleTaskConflictAnswerService;
        this.taskDetailAnswerService = taskDetailAnswerService;
        this.delegatedDecisionService = delegatedDecisionService;
        this.conversationContextService = conversationContextService;
        this.placeAliasService = placeAliasService;
        this.clock = clock;
    }

    /** Optional setter keeps legacy direct-construction unit tests source-compatible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDecisionTraceService(IntentDecisionTraceService decisionTraceService) {
        this.decisionTraceService = decisionTraceService;
    }

    /** Optional during the additive rollout so existing direct-construction tests remain compatible. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConversationFocusIntentExecutor(
            com.aproject.aidriven.mymobilesecretary.conversation.application
                    .ConversationFocusIntentExecutor executor) {
        this.conversationFocusIntentExecutor = executor;
    }

    /** Optional setter keeps legacy direct-construction tests independent from persistence. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConversationIntentReplayService(
            com.aproject.aidriven.mymobilesecretary.conversation.application
                    .ConversationIntentReplayService service) {
        this.conversationIntentReplayService = service;
    }

    /** Optional injection preserves the existing constructor and keeps shadow routing removable. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCapabilityShadowRouter(CapabilityShadowRouter capabilityShadowRouter) {
        this.capabilityShadowRouter = capabilityShadowRouter;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFamilyMessageService(
            com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService service) {
        this.familyMessageService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFamilyPersonService(
            com.aproject.aidriven.mymobilesecretary.family.application.FamilyPersonService service) {
        this.familyPersonService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setActivityMutationDisambiguationService(
            ActivityMutationDisambiguationService service) {
        this.activityMutationDisambiguationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSchedulePlaceBindingAnswerService(SchedulePlaceBindingAnswerService service) {
        this.schedulePlaceBindingAnswerService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEventIntakeService(
            com.aproject.aidriven.mymobilesecretary.event.application.EventIntakeService service) {
        this.eventIntakeService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVenueVisitInformationService(
            com.aproject.aidriven.mymobilesecretary.venue.application
                    .VenueVisitInformationService service) {
        this.venueVisitInformationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSuspensionService(
            com.aproject.aidriven.mymobilesecretary.safety.application.WorkSchoolSuspensionService service) {
        this.suspensionService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLifestyleWindowConversationService(LifestyleWindowConversationService service) {
        this.lifestyleWindowConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setLunarCalendarConversationService(LunarCalendarConversationService service) {
        this.lunarCalendarConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConditionalRecurrenceConversationService(
            ConditionalRecurrenceConversationService service) {
        this.conditionalRecurrenceConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMonthlyOrdinalRecurrenceConversationService(
            MonthlyOrdinalRecurrenceConversationService service) {
        this.monthlyOrdinalRecurrenceConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFamilyTransportConversationService(FamilyTransportConversationService service) {
        this.familyTransportConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSchoolTransportConversationService(SchoolTransportConversationService service) {
        this.schoolTransportConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScheduleCorrectionConversationService(ScheduleCorrectionConversationService service) {
        this.scheduleCorrectionConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBoundedFreeSlotConversationService(BoundedFreeSlotConversationService service) {
        this.boundedFreeSlotConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUncertainScheduleConditionConversationService(
            UncertainScheduleConditionConversationService service) {
        this.uncertainScheduleConditionConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConditionalVenueConversationService(ConditionalVenueConversationService service) {
        this.conditionalVenueConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBankTransferService(
            com.aproject.aidriven.mymobilesecretary.payment.application.BankTransferService service) {
        this.bankTransferService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPaymentNoticeService(
            com.aproject.aidriven.mymobilesecretary.payment.application.PaymentNoticeService service) {
        this.paymentNoticeService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDraftRetentionService(
            com.aproject.aidriven.mymobilesecretary.draft.application.DraftRetentionConversationService service) {
        this.draftRetentionService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTimeDisplayPreferenceService(
            com.aproject.aidriven.mymobilesecretary.shared.time.TimeDisplayPreferenceService service) {
        this.timeDisplayPreferenceService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setQuotedPlaceCorrectionService(QuotedPlaceCorrectionConversationService service) {
        this.quotedPlaceCorrectionService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUniversalLifeRecordService(
            com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService service) {
        this.universalLifeRecordService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUtilityBillService(
            com.aproject.aidriven.mymobilesecretary.utility.application.UtilityBillService service) {
        this.utilityBillService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPlanningItemTypeAnswerService(PlanningItemTypeAnswerService service) {
        this.planningItemTypeAnswerService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setScheduleSelectionConversationService(ScheduleSelectionConversationService service) {
        this.scheduleSelectionConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKnowledgeRecordDeletionService(
            com.aproject.aidriven.mymobilesecretary.knowledge.application.KnowledgeRecordDeletionService service) {
        this.knowledgeRecordDeletionService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setKnowledgeRecordEditingService(
            com.aproject.aidriven.mymobilesecretary.knowledge.application.KnowledgeRecordEditingService service) {
        this.knowledgeRecordEditingService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setProductDraftCompletionService(
            com.aproject.aidriven.mymobilesecretary.knowledge.application
                            .ProductDraftCompletionConversationService
                    service) {
        this.productDraftCompletionService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTaggedRecordConversationService(
            com.aproject.aidriven.mymobilesecretary.knowledge.tag.application
                            .TaggedRecordConversationService
                    service) {
        this.taggedRecordConversationService = service;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPurchaseConversationService(PurchaseConversationService service) {
        this.purchaseConversationService = service;
    }

    /** 處理使用者的一句話,回傳做了什麼;聽不懂/退回保底的話語會記成意圖問題供開發追蹤。 */
    public IntentResult handle(String text) {
        return handle(text, "UNKNOWN");
    }

    /** Channel-aware entry point used by REST and LINE without changing the domain command. */
    public IntentResult handle(String text, String channel) {
        return handle(text, channel, () -> { });
    }

    /**
     * Channel entry point with a fail-closed hook invoked immediately before the first command
     * that may mutate business data. Webhooks use the hook to make their reservation terminal
     * before a mutation can commit; interpretation failures before this boundary remain retryable.
     */
    public IntentResult handle(String text, String channel, Runnable beforeMutation) {
        return handleWithContext(text, text, channel, beforeMutation);
    }

    /**
     * Uses bounded channel context for interpretation while retaining the exact user message for
     * audit, life-record tagging, issue capture and conversation history.
     */
    public IntentResult handleWithContext(String userText, String interpretationText,
                                          String channel, Runnable beforeMutation) {
        String effectiveText = interpretationText == null || interpretationText.isBlank()
                ? userText : interpretationText;
        UUID requestId = RequestCorrelationContext.currentId();
        long startedNanos = System.nanoTime();
        IntentFlowTrace flowTrace = new IntentFlowTrace();
        IntentResult result = null;
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(requestId);
             IntentInterpreterTelemetryContext.Scope telemetryScope =
                     IntentInterpreterTelemetryContext.open()) {
            CapabilityShadowObservation shadowObservation =
                    CapabilityShadowObservation.observe(capabilityShadowRouter, effectiveText);
            com.aproject.aidriven.mymobilesecretary.conversation.application
                            .ConversationIntentReplayService.Attempt
                    replayAttempt = conversationIntentReplayService == null
                            ? com.aproject.aidriven.mymobilesecretary.conversation.application
                                    .ConversationIntentReplayService.Attempt.disabled()
                            : conversationIntentReplayService.begin(
                                    requestId, userText, effectiveText);
            MutationBoundary mutationBoundary = new MutationBoundary(() -> {
                replayAttempt.beforeMutation();
                beforeMutation.run();
            });
            try {
                Optional<IntentResult> replay = replayAttempt.replay();
                if (replay.isPresent()) {
                    result = replay.get();
                    flowTrace.complete(result);
                    return result;
                }
                conversationContextService.beginExchange();
                // 場合祝賀在記錄之前套用:意圖問題與上下文都要記使用者實際看到的回覆
                result = OccasionGreeting.decorate(userText,
                        doHandle(userText, effectiveText, flowTrace, mutationBoundary));
                if (venueVisitInformationService != null) {
                    result = venueVisitInformationService.decorateScheduleResult(result);
                }
                if (timeDisplayPreferenceService != null) {
                    result = timeDisplayPreferenceService.apply(result);
                }
                flowTrace.complete(result);
                replayAttempt.complete(result);
                recordLifeUtteranceSafely(userText, result);
                recordIssueIfUnresolved(userText, result);
                conversationContextService.rememberExchange(userText, result);
                return result;
            } catch (RuntimeException exception) {
                conversationContextService.abandonExchange();
                replayAttempt.failBeforeExecution();
                flowTrace.unexpectedFailure();
                throw exception;
            } finally {
                recordDecisionTraceSafely(requestId, channel, userText, result, flowTrace,
                        telemetryScope.snapshot(), shadowObservation, startedNanos);
            }
        }
    }

    private void recordLifeUtteranceSafely(String text, IntentResult result) {
        if (universalLifeRecordService == null
                || result == null
                || result.focusDirective() == ConversationFocusDirective.FOCUS_CONTROL_ONLY) {
            return;
        }
        try {
            universalLifeRecordService.recordUtterance(text, result);
        } catch (RuntimeException exception) {
            log.warn("Universal life-record tagging failed ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private IntentResult doHandle(String text, String interpretationText, IntentFlowTrace flowTrace,
                                  MutationBoundary mutationBoundary) {
        Optional<IntentCommand.Type> focusControl =
                ConversationFocusControlPhrasePolicy.classify(text);
        if (focusControl.isPresent()) {
            return executeExplicitFocusControl(
                    text, focusControl.orElseThrow(), flowTrace, mutationBoundary);
        }
        // Domain continuations run before generic feedback classification. A correction can still
        // contain the missing answer or a reference question that should complete the user's work.
        if (schoolTransportConversationService != null) {
            Optional<IntentResult> schoolTransport = schoolTransportConversationService.answer(
                    text, mutationBoundary::beforeMutation);
            if (schoolTransport.isPresent()) return schoolTransport.get();
        }
        if (quotedPlaceCorrectionService != null) {
            Optional<IntentResult> placeCorrection = quotedPlaceCorrectionService.answer(
                    text, interpretationText, mutationBoundary::beforeMutation);
            if (placeCorrection.isPresent()) return placeCorrection.get();
        }
        // 產品更正必須先於所有 pending draft，否則「你沒聽懂這個草稿」會被草稿狀態機消耗。
        Optional<IntentResult> productFeedback = ProductFeedbackBoundary.answer(text);
        if (productFeedback.isPresent()) {
            return productFeedback.get();
        }
        if (scheduleSelectionConversationService != null) {
            Optional<IntentResult> selected = scheduleSelectionConversationService.answer(
                    text, interpretationText, mutationBoundary::beforeMutation);
            if (selected.isPresent()) return selected.get();
        }
        if (planningItemTypeAnswerService != null) {
            Optional<IntentResult> planningType = planningItemTypeAnswerService.answer(
                    text, interpretationText);
            if (planningType.isPresent()) return planningType.get();
        }
        if (knowledgeRecordEditingService != null) {
            Optional<IntentResult> editing = knowledgeRecordEditingService.answer(
                    text, mutationBoundary::beforeMutation);
            if (editing.isPresent()) return editing.get();
        }
        if (knowledgeRecordDeletionService != null) {
            Optional<IntentResult> deletion = knowledgeRecordDeletionService.answer(
                    text, mutationBoundary::beforeMutation);
            if (deletion.isPresent()) return deletion.get();
        }
        if (productDraftCompletionService != null) {
            Optional<IntentResult> completion = productDraftCompletionService.answer(
                    text, mutationBoundary::beforeMutation);
            if (completion.isPresent()) return completion.get();
        }
        if (timeDisplayPreferenceService != null) {
            Optional<IntentResult> displayPreference = timeDisplayPreferenceService.answer(
                    text, mutationBoundary::beforeMutation);
            if (displayPreference.isPresent()) return displayPreference.get();
        }
        if (utilityBillService != null) {
            Optional<IntentResult> utilityBill = utilityBillService.answer(
                    text, mutationBoundary::beforeMutation);
            if (utilityBill.isPresent()) return utilityBill.get();
        }
        if (taggedRecordConversationService != null) {
            Optional<IntentResult> taggedRecord =
                    taggedRecordConversationService.answerExplicit(text);
            if (taggedRecord.isPresent()) return taggedRecord.get();
        }
        if (purchaseConversationService != null) {
            Optional<IntentResult> purchase = purchaseConversationService.answer(
                    text, interpretationText);
            if (purchase.isPresent()) return purchase.get();
        }
        if (draftRetentionService != null) {
            Optional<IntentResult> retention = draftRetentionService.answer(
                    text, mutationBoundary::beforeMutation);
            if (retention.isPresent()) return retention.get();
        }
        if (paymentNoticeService != null) {
            Optional<IntentResult> paymentNotice = paymentNoticeService.answer(
                    text, mutationBoundary::beforeMutation);
            if (paymentNotice.isPresent()) return paymentNotice.get();
        }
        if (suspensionService != null) {
            Optional<IntentResult> suspension = suspensionService.answer(
                    text, mutationBoundary::beforeMutation);
            if (suspension.isPresent()) return suspension.get();
        }
        if (scheduleCorrectionConversationService != null) {
            Optional<IntentResult> correction = scheduleCorrectionConversationService.answer(
                    text, mutationBoundary::beforeMutation);
            if (correction.isPresent()) {
                return correction.get();
            }
        }
        if (boundedFreeSlotConversationService != null) {
            Optional<IntentResult> freeSlots = boundedFreeSlotConversationService.answer(text);
            if (freeSlots.isPresent()) {
                return freeSlots.get();
            }
        }
        if (uncertainScheduleConditionConversationService != null) {
            Optional<IntentResult> uncertain = uncertainScheduleConditionConversationService.answer(text);
            if (uncertain.isPresent()) {
                return uncertain.get();
            }
        }
        if (conditionalVenueConversationService != null) {
            Optional<IntentResult> conditionalVenue = conditionalVenueConversationService.answer(
                    text, conversationContextService.snapshot(), mutationBoundary::beforeMutation);
            if (conditionalVenue.isPresent()) {
                return conditionalVenue.get();
            }
        }
        if (schedulePlaceBindingAnswerService != null) {
            Optional<IntentResult> binding = schedulePlaceBindingAnswerService.answer(text);
            if (binding.isPresent()) {
                return binding.get();
            }
        }
        if (familyTransportConversationService != null) {
            Optional<IntentResult> familyTransport = familyTransportConversationService.answer(
                    text, mutationBoundary::beforeMutation);
            if (familyTransport.isPresent()) {
                return familyTransport.get();
            }
        }
        if (bankTransferService != null) {
            Optional<IntentResult> transfer = bankTransferService.answer(
                    text, mutationBoundary::beforeMutation);
            if (transfer.isPresent()) {
                return transfer.get();
            }
        }
        if (lifestyleWindowConversationService != null) {
            Optional<IntentResult> lifestyle = lifestyleWindowConversationService.answer(
                    text, mutationBoundary::beforeMutation);
            if (lifestyle.isPresent()) {
                return lifestyle.get();
            }
        }
        if (conditionalRecurrenceConversationService != null) {
            Optional<IntentResult> conditional = conditionalRecurrenceConversationService.answer(
                    text, conversationContextService.snapshot(), mutationBoundary::beforeMutation);
            if (conditional.isPresent()) {
                return conditional.get();
            }
        }
        if (monthlyOrdinalRecurrenceConversationService != null) {
            Optional<IntentResult> monthly = monthlyOrdinalRecurrenceConversationService.answer(
                    text, conversationContextService.snapshot(), mutationBoundary::beforeMutation);
            if (monthly.isPresent()) {
                return monthly.get();
            }
        }
        if (lunarCalendarConversationService != null) {
            Optional<IntentResult> lunar = lunarCalendarConversationService.answer(text);
            if (lunar.isPresent()) {
                return lunar.get();
            }
        }
        Optional<IntentResult> calendarDate = CalendarDatePolicy.answer(text, clock);
        if (calendarDate.isPresent()) {
            return calendarDate.get();
        }
        Optional<String> dateClarification = CalendarDatePolicy.clarification(text, clock);
        if (dateClarification.isPresent()) {
            return IntentResult.clarificationNeeded(dateClarification.get());
        }
        if (venueVisitInformationService != null) {
            Optional<IntentResult> venueInformation = venueVisitInformationService.answer(
                    text, mutationBoundary::beforeMutation);
            if (venueInformation.isPresent()) {
                return venueInformation.get();
            }
        }
        if (eventIntakeService != null) {
            Optional<IntentResult> event = eventIntakeService.answer(
                    interpretationText, mutationBoundary::beforeMutation);
            if (event.isPresent()) {
                return event.get();
            }
        }
        if (familyPersonService != null) {
            Optional<IntentResult> person = familyPersonService.answer(
                    text, mutationBoundary::beforeMutation);
            if (person.isPresent()) {
                return person.get();
            }
            familyPersonService.observeMentions(text, mutationBoundary::beforeMutation);
        }
        if (familyMessageService != null) {
            Optional<IntentResult> family = familyMessageService.answer(
                    text, mutationBoundary::beforeMutation);
            if (family.isPresent()) {
                return family.get();
            }
        }
        Optional<IntentResult> failureExplanation = FailureExplanationService.answer(
                text, conversationContextService.snapshot());
        if (failureExplanation.isPresent()) {
            return failureExplanation.get();
        }
        // 當前行程問題的明確拒絕／確認不得被過期的其他類型草稿搶答。
        if (isScheduleMergeRejection(text)) {
            return dailyScheduleOverviewService.rejectMerge(text);
        }
        if (isScheduleMergeConfirmation(text)) {
            mutationBoundary.beforeMutation();
            return dailyScheduleOverviewService.confirmMerge();
        }
        if (asksWhatContainedItemMeans(text)) {
            return dailyScheduleOverviewService.explainContainedItems();
        }
        Optional<IntentResult> activityCount = activityCountAnswerService.answer(text);
        if (activityCount.isPresent()) {
            return activityCount.get();
        }
        Optional<IntentResult> lastActivity = lastActivityAnswerService.answer(text);
        if (lastActivity.isPresent()) {
            return lastActivity.get();
        }
        if (activityMutationDisambiguationService != null) {
            Optional<IntentResult> ambiguity = activityMutationDisambiguationService.answer(text);
            if (ambiguity.isPresent()) {
                return ambiguity.get();
            }
        }
        Optional<IntentResult> taskConflict = scheduleTaskConflictAnswerService.answer(text);
        if (taskConflict.isPresent()) {
            return taskConflict.get();
        }
        Optional<IntentResult> reminderTiming = reminderTimingAnswerService.answer(text);
        if (reminderTiming.isPresent()) {
            return reminderTiming.get();
        }
        Optional<IntentResult> taskDetail = taskDetailAnswerService.answer(text);
        if (taskDetail.isPresent()) {
            return taskDetail.get();
        }
        Optional<List<LocalDate>> overviewDates = dailyScheduleDates(text, clock);
        if (overviewDates.isPresent()) {
            return dailyScheduleOverviewService.overview(overviewDates.get());
        }
        // 「你自己看著辦」=授權低風險安排並回報(使用者裁決 #48)
        if (isDecisionDelegation(text)) {
            mutationBoundary.beforeMutation();
            return delegatedDecisionService.decide();
        }
        Optional<String> routineQuestion = recurringRoutineClarification(text);
        if (routineQuestion.isPresent()) {
            return IntentResult.clarificationNeeded(routineQuestion.get());
        }
        Optional<IntentResult> help = capabilityHelp(text, conversationContextService.snapshot());
        if (help.isPresent()) {
            return help.get();
        }
        Optional<IntentResult> knownPlace = answerKnownPlaceQuestion(text);
        if (knownPlace.isPresent()) {
            return knownPlace.get();
        }
        IntentScript script;
        IntentInterpreter interpreter = interpreterProvider.getIfAvailable();
        if (interpreter == null) {
            flowTrace.validationFailed("INTERPRETER_NOT_CONFIGURED");
            return interpreterFailureFallback(text, "意圖解析未啟用", mutationBoundary);
        }
        try {
            script = interpreter.interpret(text, interpretationText, Instant.now(clock),
                    conversationContextService.snapshot());
            script = IntentScriptSafetyPolicy.apply(text, script, clock);
            script = IntentScriptDateRangePolicy.apply(text, script, Instant.now(clock));
        } catch (Exception e) {
            log.warn("Intent interpretation failed ({}); applying safe fallback",
                    e.getClass().getSimpleName());
            flowTrace.validationFailed("INTERPRETER_FAILURE");
            return interpreterFailureFallback(text, "AI 暫時無法使用", mutationBoundary);
        }
        if (script == null || script.commands() == null || script.commands().isEmpty()) {
            flowTrace.validationFailed("EMPTY_INTERPRETATION");
            return interpreterFailureFallback(text, "解析結果是空的", mutationBoundary);
        }
        script = IntentScriptCompletenessPolicy.apply(text, script);
        Optional<IntentResult> feedbackOnly = collapseFeedbackOnlyScript(script);
        if (feedbackOnly.isPresent()) {
            flowTrace.select(new IntentCommand(
                    IntentCommand.Type.FEEDBACK, null, null, null, null, null,
                    null, null, null, null, null, null, null));
            flowTrace.validationPassed();
            return feedbackOnly.get();
        }

        // 單一操作:維持原語意(驗證失敗 → 整句保底)
        if (script.commands().size() == 1) {
            IntentCommand command = script.commands().get(0);
            flowTrace.select(command);
            try {
                mutationBoundary.before(command);
                IntentResult executed = execute(text, command);
                flowTrace.validationPassed();
                return executed;
            } catch (IllegalArgumentException e) {
                // LLM 輸出未通過驗證(時間格式爛、缺欄位)→ 同樣不丟資料
                String validationCode = IntentValidationDiagnostic.code(e);
                log.warn("Intent command invalid [code={}]; applying safe fallback", validationCode);
                flowTrace.validationRejected(validationCode);
                return safeFallback(text, "解析結果不完整",
                        IntentValidationDiagnostic.explain(e), command, mutationBoundary);
            } catch (com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException e) {
                // 業務錯誤(如 Google 查不到地點)→ 轉成可讀回覆;
                // 絕不能往 webhook 洩漏成非 200,否則 LINE 會重送整包事件
                log.warn("Intent command hit business rule [code={}]", e.getCode());
                flowTrace.validationRejected(e.getCode());
                return IntentResult.clarificationNeeded(e.getMessage());
            }
        }

        // 多操作(「取消A,B也取消,C改到11點」):逐一執行,單項失敗不拖垮其他項
        java.util.List<String> lines = new java.util.ArrayList<>();
        int failed = 0;
        flowTrace.selectBatch(script.commands());
        for (IntentCommand command : script.commands()) {
            try {
                mutationBoundary.before(command);
                lines.add(execute(text, command).message());
            } catch (IllegalArgumentException e) {
                log.warn("Batch intent command invalid ({})", e.getClass().getSimpleName());
                flowTrace.validationRejected(IntentValidationDiagnostic.code(e));
                failed++;
                lines.add("有一項我處理不了,請單獨再講一次。");
            } catch (com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException e) {
                log.warn("Batch intent command hit business rule ({})", e.getCode());
                flowTrace.validationRejected(e.getCode());
                failed++;
                lines.add("有一項我處理不了,請單獨再講一次。");
            } catch (Exception e) {
                log.warn("Batch intent command failed ({})", e.getClass().getSimpleName());
                flowTrace.validationFailed("BATCH_COMMAND_FAILURE");
                failed++;
                lines.add("有一項我處理不了,請單獨再講一次。");
            }
        }
        if (failed == 0) {
            flowTrace.validationPassed();
        }
        if (failed == script.commands().size()) {
            return safeFallback(text, "多項操作都解析失敗", mutationBoundary);
        }
        return IntentResult.batchExecuted(lines);
    }

    private static Optional<IntentResult> collapseFeedbackOnlyScript(IntentScript script) {
        java.util.List<IntentCommand> commands = script.commands().stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean hasFeedback = commands.stream()
                .map(IntentCommand::type)
                .anyMatch(IntentCommand.Type.FEEDBACK::equals);
        boolean hasUnknown = commands.stream()
                .map(IntentCommand::type)
                .anyMatch(IntentCommand.Type.UNKNOWN::equals);
        boolean onlyFeedbackOrUnknown = commands.stream()
                .map(IntentCommand::type)
                .allMatch(type -> type == IntentCommand.Type.FEEDBACK
                        || type == IntentCommand.Type.UNKNOWN);
        // A single typed FEEDBACK command may carry a server-recognized continuation reason
        // (for example MISSING_PLACE or DUPLICATE). It must reach ActivityIntentHandler so the
        // handler can inspect current actor-scoped context without mutating domain data. Only a
        // mixed feedback/unknown script is collapsed to prevent an LLM-added UNKNOWN command from
        // turning product feedback into a clarification or fallback mutation path.
        return hasFeedback && hasUnknown && onlyFeedbackOrUnknown
                ? Optional.of(IntentResult.feedbackReceived())
                : Optional.empty();
    }

    private IntentResult executeExplicitFocusControl(
            String text, IntentCommand.Type type, IntentFlowTrace flowTrace,
            MutationBoundary mutationBoundary) {
        IntentCommand command = new IntentCommand(
                type, null, null, null, null, null, null, null,
                null, null, null, null, false, IntentOptions.empty(), text);
        flowTrace.select(command);
        mutationBoundary.before(command);
        IntentResult result = execute(text, command);
        flowTrace.validationPassed();
        return result;
    }

    static Optional<LocalDate> dailyScheduleDate(String text, Clock clock) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        String withoutEndingPunctuation = normalized.replaceFirst("[?？。!！]+$", "");
        Optional<LocalDate> targetDate = relativeScheduleDate(normalized, clock);
        boolean hasSchedule = normalized.contains("行程");
        boolean modifying = normalized.contains("建立") || normalized.contains("新增")
                || normalized.contains("安排一個") || normalized.contains("排一個")
                || normalized.contains("幫我排") || normalized.contains("取消")
                || normalized.contains("刪除") || normalized.contains("刪掉")
                || normalized.contains("改期") || normalized.contains("改到")
                || normalized.contains("改成") || normalized.contains("移到")
                || normalized.contains("延後") || normalized.contains("提前");
        boolean asking = normalized.contains("總整") || normalized.contains("總覽")
                || normalized.contains("列出") || normalized.contains("有什麼行程")
                || normalized.contains("行程有哪些")
                || normalized.contains("查看行程") || normalized.contains("看看行程")
                || (normalized.contains("固定行程") && normalized.contains("當日行程"))
                || normalized.contains("給我")
                || withoutEndingPunctuation.endsWith("的行程")
                || withoutEndingPunctuation.endsWith("行程");
        if (targetDate.isEmpty() || !hasSchedule || modifying || !asking) {
            return Optional.empty();
        }
        return targetDate;
    }

    static Optional<List<LocalDate>> dailyScheduleDates(String text, Clock clock) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        boolean weekend = normalized.contains("週末") || normalized.contains("周末");
        if (!weekend) {
            return dailyScheduleDate(text, clock).map(List::of);
        }
        String withoutEndingPunctuation = normalized.replaceFirst("[?？。!！]+$", "");
        boolean hasSchedule = normalized.contains("行程");
        boolean modifying = containsAny(normalized,
                "建立", "新增", "安排一個", "排一個", "幫我排", "取消", "刪除", "刪掉",
                "改期", "改到", "改成", "移到", "延後", "提前");
        boolean asking = containsAny(normalized,
                "總整", "總覽", "列出", "有什麼行程", "行程有哪些", "查看行程", "看看行程",
                "排了哪些", "排了什麼", "安排了哪些", "給我")
                || withoutEndingPunctuation.endsWith("的行程")
                || withoutEndingPunctuation.endsWith("行程");
        if (!hasSchedule || modifying || !asking) {
            return Optional.empty();
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Taipei")));
        LocalDate monday = today.with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        boolean nextWeekend = normalized.contains("下週末") || normalized.contains("下周末")
                || normalized.contains("下個週末") || normalized.contains("下个周末");
        LocalDate saturday = monday.plusDays(nextWeekend ? 12 : 5);
        return Optional.of(List.of(saturday, saturday.plusDays(1)));
    }

    static Optional<LocalDate> relativeScheduleDate(String normalizedText, Clock clock) {
        String normalized = normalizedText == null ? "" : normalizedText.replaceAll("\\s+", "");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Taipei")));
        if (normalized.contains("大前天")) return Optional.of(today.minusDays(3));
        if (normalized.contains("前天")) return Optional.of(today.minusDays(2));
        if (normalized.contains("昨天") || normalized.contains("昨日")) return Optional.of(today.minusDays(1));
        if (normalized.contains("今天") || normalized.contains("今日")) return Optional.of(today);
        if (normalized.contains("大後天") || normalized.contains("大后天")) return Optional.of(today.plusDays(3));
        if (normalized.contains("後天") || normalized.contains("后天")) return Optional.of(today.plusDays(2));
        if (normalized.contains("明天") || normalized.contains("明日")) return Optional.of(today.plusDays(1));

        java.util.regex.Matcher previousWeekday = java.util.regex.Pattern
                .compile("(?:上週|上周|上禮拜|上個禮拜|上星期|上個星期)([一二三四五六日天])")
                .matcher(normalized);
        if (previousWeekday.find()) {
            return Optional.of(weekdayInWeek(today.minusWeeks(1), previousWeekday.group(1)));
        }
        java.util.regex.Matcher currentWeekday = java.util.regex.Pattern
                .compile("(?:這週|这周|本週|本周|這禮拜|这个礼拜|這星期|本星期)([一二三四五六日天])")
                .matcher(normalized);
        if (currentWeekday.find()) {
            return Optional.of(weekdayInWeek(today, currentWeekday.group(1)));
        }
        java.util.regex.Matcher bareWeekday = java.util.regex.Pattern
                .compile("(?:週|周|星期|禮拜)([一二三四五六日天])")
                .matcher(normalized);
        if (bareWeekday.find()) {
            LocalDate candidate = weekdayInWeek(today, bareWeekday.group(1));
            return Optional.of(candidate.isBefore(today) ? candidate.plusWeeks(1) : candidate);
        }
        return Optional.empty();
    }

    static boolean asksWhatContainedItemMeans(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("當日項目")
                && containsAny(normalized, "是指", "是什麼", "是哪個", "哪一個", "哪個");
    }

    private static LocalDate weekdayInWeek(LocalDate reference, String chineseWeekday) {
        int day = switch (chineseWeekday) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "日", "天" -> 7;
            default -> throw new IllegalArgumentException("unsupported weekday: " + chineseWeekday);
        };
        LocalDate monday = reference.with(
                java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        return monday.plusDays(day - 1L);
    }

    /** 「你自己看著辦」「你決定就好」:委任語,授權系統低風險安排(裁決 #48)。 */
    static boolean isDecisionDelegation(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("看著辦")
                || normalized.contains("你決定")
                || normalized.contains("交給你決定")
                || normalized.contains("幫我決定")
                || normalized.contains("隨便你");
    }

    /** 「簡報排練不要併到上班固定行程」「不要併入」:拒絕合併提案,交回使用者決定時間。 */
    static boolean isScheduleMergeRejection(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("不要併")
                || normalized.contains("不併入")
                || normalized.contains("別併入")
                || normalized.contains("不要合併")
                || normalized.contains("取消併入");
    }

    static boolean isScheduleMergeConfirmation(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("確認併入")
                || normalized.contains("確認合併")
                || normalized.contains("併入固定行程");
    }

    static Optional<String> capabilityHelp(String text) {
        return capabilityHelp(text, ConversationSnapshot.empty()).map(IntentResult::message);
    }

    static Optional<IntentResult> capabilityHelp(String text, ConversationSnapshot snapshot) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        boolean asking = normalized.contains("能力範圍")
                || normalized.contains("功能介紹")
                || normalized.contains("你會什麼")
                || normalized.contains("你能做什麼")
                || normalized.contains("你可以做什麼")
                || normalized.contains("你現在能做到什麼")
                || normalized.contains("你能做到什麼")
                || normalized.contains("你現在能協助哪些事情")
                || normalized.contains("你能協助哪些事情")
                || normalized.contains("你可以協助哪些事情")
                || normalized.contains("你能幫哪些忙")
                || normalized.contains("你可以幫哪些忙")
                || normalized.contains("你支援哪些事情")
                || normalized.contains("你可以幫我做什麼")
                || normalized.contains("有哪些功能")
                || normalized.equals("怎麼用")
                || normalized.equals("要怎麼用");
        if (asking) {
            return Optional.of(IntentResult.message(IntentResult.Action.CAPABILITY_HELP_MENU, """
                    我可以從這 5 類開始幫你：
                    1. 待辦與任務
                    2. 行程與空檔
                    3. 地點與順路事項
                    4. 購物、庫存與價格
                    5. 提醒、勿擾與條件監看
                    請選一類；你可以回覆數字或類別名稱。
                    """.strip()));
        }
        String previousAction = snapshot == null ? null : snapshot.lastAction();
        if (IntentResult.Action.CAPABILITY_HELP_MENU.name().equals(previousAction)) {
            IntentResult.Action topic = capabilityTopic(normalized);
            if (topic != null) {
                return Optional.of(capabilityTopicReply(topic, "建立"));
            }
        }
        IntentResult.Action previousTopic = capabilityTopicAction(previousAction);
        String operation = capabilityOperation(normalized);
        if (previousTopic != null && operation != null) {
            return Optional.of(capabilityTopicReply(previousTopic, operation));
        }
        return Optional.empty();
    }

    private static IntentResult.Action capabilityTopic(String text) {
        if (text.matches("(?:第)?(?:1|一)(?:類|個)?") || containsAny(text, "待辦", "任務")) {
            return IntentResult.Action.CAPABILITY_HELP_TASK;
        }
        if (text.matches("(?:第)?(?:2|二)(?:類|個)?") || containsAny(text, "行程", "空檔")) {
            return IntentResult.Action.CAPABILITY_HELP_CALENDAR;
        }
        if (text.matches("(?:第)?(?:3|三)(?:類|個)?") || containsAny(text, "地點", "順路")) {
            return IntentResult.Action.CAPABILITY_HELP_PLACE;
        }
        if (text.matches("(?:第)?(?:4|四)(?:類|個)?") || containsAny(text, "購物", "庫存", "價格")) {
            return IntentResult.Action.CAPABILITY_HELP_SHOPPING;
        }
        if (text.matches("(?:第)?(?:5|五)(?:類|個)?") || containsAny(text, "提醒", "勿擾", "監看")) {
            return IntentResult.Action.CAPABILITY_HELP_REMINDER;
        }
        return null;
    }

    private static IntentResult.Action capabilityTopicAction(String action) {
        if (action == null) return null;
        try {
            IntentResult.Action candidate = IntentResult.Action.valueOf(action);
            return switch (candidate) {
                case CAPABILITY_HELP_TASK, CAPABILITY_HELP_CALENDAR, CAPABILITY_HELP_PLACE,
                        CAPABILITY_HELP_SHOPPING, CAPABILITY_HELP_REMINDER -> candidate;
                default -> null;
            };
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String capabilityOperation(String text) {
        if (containsAny(text, "建立", "新增")) return "建立";
        if (containsAny(text, "修改", "調整", "改動")) return "修改";
        if (containsAny(text, "刪除", "取消", "移除")) return "刪除";
        if (containsAny(text, "查詢", "查看", "列出", "怎麼查")) return "查詢";
        return null;
    }

    private static IntentResult capabilityTopicReply(IntentResult.Action topic, String operation) {
        String label;
        String example;
        switch (topic) {
            case CAPABILITY_HELP_TASK -> {
                label = "待辦";
                example = switch (operation) {
                    case "修改" -> "「把買牛奶的期限改成明天晚上八點」";
                    case "刪除" -> "「取消買牛奶」";
                    case "查詢" -> "「還有什麼待辦？」";
                    default -> "「提醒我明天下午六點買牛奶」";
                };
            }
            case CAPABILITY_HELP_CALENDAR -> {
                label = "行程";
                example = switch (operation) {
                    case "修改" -> "「把產品會議改到明天下午四點」";
                    case "刪除" -> "「取消明天的產品會議」";
                    case "查詢" -> "「這週末有什麼行程？」";
                    default -> "「幫我建立明天下午三點到四點的產品會議行程」";
                };
            }
            case CAPABILITY_HELP_PLACE -> {
                label = "地點";
                example = switch (operation) {
                    case "修改" -> "「把公司地址更新成……」";
                    case "刪除" -> "地點目前不會直接刪除；可說「把拿包裹的地點移除」解除待辦綁定。";
                    case "查詢" -> "「公司是指哪一個地點？」";
                    default -> "「建立地點：公司，地址是……」";
                };
            }
            case CAPABILITY_HELP_SHOPPING -> {
                label = "購物";
                example = switch (operation) {
                    case "修改" -> "「把牛奶庫存改成 2 瓶」";
                    case "刪除" -> "「把牛奶從購物清單移除」";
                    case "查詢" -> "「購物清單還有什麼？」";
                    default -> "「把牛奶和雞蛋加到購物清單」";
                };
            }
            case CAPABILITY_HELP_REMINDER -> {
                label = "提醒";
                example = switch (operation) {
                    case "修改" -> "「把勿擾時間改成晚上十點到早上七點」";
                    case "刪除" -> "「取消勿擾時間」";
                    case "查詢" -> "「現在的提醒偏好是什麼？」";
                    default -> "「明天產品會議前二十分鐘提醒我」";
                };
            }
            default -> throw new IllegalArgumentException("unsupported capability topic");
        }
        return IntentResult.message(topic, "%s%s範例：%s\n想再看修改、刪除或查詢哪一種？"
                .formatted(label, operation, example));
    }

    static Optional<String> recurringRoutineClarification(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        boolean routine = normalized.contains("每個上班日")
                && (normalized.contains("日常行程") || normalized.contains("日常安排"))
                && (normalized.contains("上班") || normalized.contains("通勤"));
        if (!routine) return Optional.empty();
        boolean hasDependentTransport = TransportSemanticPolicy
                .isTransportToDependentActivity(normalized);
        return Optional.of("""
                我知道你要記的是「上班日固定生活時段」，而且親自到場的事情不能排進通勤與上班區間；我不會把這段當成一般回饋，也不會先猜時間建立。
                建立前請一次確認這 4 點：
                1. 上班日固定是週一到週五嗎？國定假日是否略過？
                2. %s
                3. 通常幾點離開工作地點？若不同星期不同，請分別說明。
                4. 哪些是你本人被占用的時間段，哪些只是出發、抵達或接送的定點提醒？
                你回答後，我再依參與角色拆成固定時間段與定點提醒；沒有明講的地點、接送或天氣條件都不會自行加入。
                """.formatted(hasDependentTransport
                        ? "接送完成後，工作地點在哪裡，最晚幾點要到？"
                        : "工作地點在哪裡，最晚幾點要到？").strip());
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    /** 依驗證後的 command 執行;LLM 輸出一律先驗證再信。 */
    private IntentResult execute(String text, IntentCommand command) {
        if (command == null || command.type() == null) {
            return IntentResult.clarificationNeeded("我沒有解析出可執行的指令,請換個說法。");
        }
        Optional<IntentResult> vagueTime = VagueTimeGuard.clarify(text, command);
        if (vagueTime.isPresent()) {
            return vagueTime.get();
        }
        if (command.type() == IntentCommand.Type.UNKNOWN) {
            return IntentResult.clarificationNeeded(
                    userFacingUnknownReason(command.reason()));
        }
        if (conversationFocusIntentExecutor == null) {
            return intentHandlerRegistry.dispatch(text, command);
        }
        return conversationFocusIntentExecutor.execute(text, command,
                com.aproject.aidriven.mymobilesecretary.conversation.application
                        .ConversationInboundIdempotency.fromRequestId(
                                RequestCorrelationContext.currentId()));
    }

    static String userFacingUnknownReason(String reason) {
        if (reason == null || reason.isBlank()) return "我沒聽懂，可以換個說法嗎？";
        String compact = reason.replaceAll("\\s+", "");
        if (looksLikeInternalDiagnostic(reason, compact)) {
            return "我還需要補充資訊才能處理；請告訴我名稱、日期時間、地點或你要做的動作。";
        }
        if (containsAny(compact, "使用者是在", "使用者已", "系統應", "無法對應到任何能力",
                "不是要建立", "目前無法直接判定", "才能執行")) {
            return "我知道你是在追問上一則回覆，但我還沒有唯一對到你指的項目；請直接告訴我名稱或清單編號。";
        }
        return reason;
    }

    private static boolean looksLikeInternalDiagnostic(String reason, String compact) {
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        return lower.matches(".*(?:[a-z_][a-z0-9_]*\\.){2,}[a-z_$][a-z0-9_$]*.*")
                || lower.matches(".*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*")
                || lower.matches(".*\\b[0-9a-f]{40,}\\b.*")
                || containsAny(lower, "exception", "stacktrace", "stack trace", "sql ",
                        "select ", "insert ", "update ", "delete ", " column ",
                        "constraint", "jdbc", "hibernate", "bearer ", "authorization",
                        "api_key", "api-token", "api_token", "access_token")
                || containsAny(compact, "資料庫欄位", "內部類別", "套件名稱", "存取權杖");
    }

    /** Builds a bounded trace; any assembly or persistence failure is isolated from the reply. */
    private void recordDecisionTraceSafely(UUID requestId, String channel, String input,
                                           IntentResult result, IntentFlowTrace flowTrace,
                                           IntentInterpreterTelemetryContext.Telemetry telemetry,
                                           CapabilityShadowObservation shadowObservation,
                                           long startedNanos) {
        if (decisionTraceService == null) {
            return;
        }
        try {
            String normalizedChannel = channel == null || channel.isBlank()
                    ? "UNKNOWN"
                    : channel.strip().toUpperCase(java.util.Locale.ROOT);
            IntentDecisionTraceDraft.Builder draft = IntentDecisionTraceDraft
                    .builder(requestId, normalizedChannel)
                    .versions("legacy-router-v1",
                            telemetry == null ? null : "anthropic-prompt-v1",
                            "intent-command-v1")
                    .selectedCapability(flowTrace.selectedCapability())
                    .validationOutcome(flowTrace.validationOutcome())
                    .validationCode(flowTrace.validationCode())
                    .executionOutcome(flowTrace.executionOutcome())
                    .stageLatency("total", IntentInterpreterTelemetryContext.elapsedMillis(startedNanos))
                    .rawExchange(input, result == null ? null : result.message())
                    .redactedSummary(flowTrace.redactedSummary(result));
            if (shadowObservation.observed()) {
                draft.candidates(shadowObservation.candidateScores())
                        .shadowRouting(
                                shadowObservation.routerVersion(),
                                shadowObservation.disposition(),
                                shadowObservation.fallbackReason(),
                                shadowObservation.promptVersion(),
                                shadowObservation.promptHash(),
                                shadowObservation.tokenEstimate(),
                                shadowObservation.contextPlan())
                        .stageLatency("shadow-routing", shadowObservation.latencyMs());
            }
            WorkspaceContextHolder.current().ifPresent(context -> draft
                    .workspaceId(context.workspaceId())
                    .actorId(context.actorId()));
            if (telemetry != null) {
                draft.modelUsage(telemetry.model(), telemetry.inputTokens(), telemetry.outputTokens());
                if (telemetry.modelLatencyMs() != null) {
                    draft.stageLatency("model", telemetry.modelLatencyMs());
                }
                if (telemetry.parsingLatencyMs() != null) {
                    draft.stageLatency("parsing", telemetry.parsingLatencyMs());
                }
            }
            decisionTraceService.recordSafely(draft.build());
        } catch (Exception exception) {
            log.warn("Intent decision trace assembly failed [requestId={}, cause={}]",
                    requestId, exception.getClass().getSimpleName());
        }
    }

    /**
     * 回問與保底都代表「這句話沒被好好服務到」→ 記成意圖問題。
     * 正常完成的意圖不記;紀錄失敗不影響回覆(IntentIssueService 內部吞錯)。
     */
    private void recordIssueIfUnresolved(String text, IntentResult result) {
        switch (result.action()) {
            case CLARIFICATION_NEEDED -> issueService.recordSafely(
                    text, result.message(), com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue.Category.CLARIFICATION);
            case FALLBACK_TASK_CREATED -> issueService.recordSafely(
                    text, result.message(), com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue.Category.FALLBACK);
            case AI_UNAVAILABLE -> issueService.recordSafely(
                    text, result.message(), com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue.Category.FALLBACK);
            case FEEDBACK_RECEIVED -> issueService.recordSafely(
                    text, result.message(), com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue.Category.FEEDBACK);
            default -> {
            }
        }
    }

    /**
     * Preserves bounded deterministic travel support when the interpreter itself is unavailable.
     * A successfully interpreted typed command never reaches this path.
     */
    private IntentResult interpreterFailureFallback(String text, String why,
                                                     MutationBoundary mutationBoundary) {
        return deterministicTravelFallback(text, mutationBoundary)
                .or(() -> taggedRecordFallback(text))
                .orElseGet(() -> safeFallback(text, why, mutationBoundary));
    }

    private Optional<IntentResult> taggedRecordFallback(String text) {
        return taggedRecordConversationService == null
                ? Optional.empty()
                : taggedRecordConversationService.answer(text);
    }

    private Optional<IntentResult> deterministicTravelFallback(
            String text, MutationBoundary mutationBoundary) {
        Optional<IntentResult> itinerary;
        if (conversationFocusIntentExecutor == null) {
            itinerary = travelItineraryDraftAnswerService.answer(
                    text, mutationBoundary::beforeMutation);
        } else {
            itinerary = conversationFocusIntentExecutor.executeResolved(
                    com.aproject.aidriven.mymobilesecretary.conversation.application
                            .ConversationInboundIdempotency.fromRequestId(
                                    RequestCorrelationContext.currentId()),
                    () -> travelItineraryDraftAnswerService.answer(
                            text, mutationBoundary::beforeMutation),
                    IntentService::travelFallbackType);
        }
        if (itinerary.isPresent()) {
            return itinerary;
        }
        Optional<IntentResult> packing = travelPackingAnswerService.answer(
                text, mutationBoundary::beforeMutation);
        return packing.isPresent() ? packing : travelPlanningIntakeService.answer(text);
    }

    private static IntentCommand.Type travelFallbackType(IntentResult result) {
        return switch (result.action()) {
            case TRAVEL_ITINERARY_CONFIRMED ->
                    IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT;
            case TRAVEL_ITINERARY_DISCARDED ->
                    IntentCommand.Type.DISCARD_TRAVEL_ITINERARY_DRAFT;
            default -> IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT;
        };
    }

    /** LLM 失敗時只替明確要求「提醒／記下」的原文建保底待辦；查詢與修改指令絕不異動資料。 */
    private IntentResult safeFallback(String text, String why,
                                      MutationBoundary mutationBoundary) {
        return safeFallback(text, why, null, null, mutationBoundary);
    }

    private IntentResult safeFallback(String text, String why,
                                      String validationReason, IntentCommand command,
                                      MutationBoundary mutationBoundary) {
        if (hasExplicitCaptureCue(text) && !looksLikeQuestion(text)) {
            mutationBoundary.beforeMutation();
            Task task = taskService.createTask(text, null, TaskPriority.NORMAL, null);
            return IntentResult.fallbackTaskCreated(task, why);
        }
        return validationReason == null
                ? IntentResult.aiUnavailable(why)
                : IntentResult.aiUnavailable(why, validationReason, command);
    }

    static boolean hasExplicitCaptureCue(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("提醒我") || normalized.contains("提醒一下")
                || normalized.contains("幫我記") || normalized.contains("記一下")
                || normalized.contains("記得") || normalized.contains("別忘")
                || normalized.contains("不要忘記") || normalized.contains("加入待辦")
                || normalized.contains("加到待辦") || normalized.contains("新增待辦")
                || normalized.contains("建立待辦");
    }

    static boolean looksLikeQuestion(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("什麼時候") || normalized.contains("何時")
                || normalized.contains("幾點") || normalized.contains("哪天")
                || normalized.contains("哪裡") || normalized.contains("多少")
                || normalized.contains("多久") || normalized.contains("有沒有")
                || normalized.contains("是不是") || normalized.contains("怎麼")
                || normalized.contains("為什麼") || normalized.contains("嗎")
                || normalized.endsWith("?") || normalized.endsWith("？");
    }

    /**
     * Read-only commands may safely be replayed when a later conversation-log write fails.
     * Everything not explicitly listed is treated as mutating, so newly added capabilities fail
     * closed until their semantics are deliberately classified.
     */
    static boolean isPotentiallyMutating(IntentCommand.Type type) {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case EXPLAIN_LAST_FAILURE, SUGGEST_FREE_SLOT, LIST_AGENDA, ASK_TASK_INFO,
                    ASK_AVAILABILITY, LIST_SCHEDULES_ON_DATE, LIST_RECENT,
                    SUGGEST_ROUTE_TASKS, LIST_SHOPPING_ITEMS, ASK_PRICE_COMPARISON,
                    ASK_WEATHER, ASK_TRAVEL_TIME, ASK_DEPARTURE_TIME, CHECK_FEASIBILITY,
                    SOCIAL, LIST_COMPLETED_TASKS, LIST_SHOPPING_BY_PLACE, AGENDA_SUMMARY,
                    LIST_INVENTORY, ASK_ITEM_PLACES, LIST_ITEMS_BY_PLACE,
                    GROUP_SHOPPING_BY_PLACE, ASK_REMINDER_PREFERENCES,
                    LIST_LOCATION_TASKS, ASK_PLACE_TASKS, ASK_TASK_GEOFENCE,
                    ASK_NEXT_SCHEDULE, ASK_SCHEDULE_GAP, GROUP_SCHEDULES_BY_DAY,
                    CHECK_SCHEDULE_CONFLICTS, SUGGEST_NEXT_TASK,
                    GROUP_TASKS_BY_CATEGORY, ASK_TASK_PROGRESS, GROUP_TASKS_BY_DUE,
                    ASK_TASK_LOAD, ASK_BUSY_TASK_DAY, ASK_BUSY_SCHEDULE_DAY,
                    ASK_LONGEST_SCHEDULE, GROUP_SCHEDULES_BY_PLACE, ASK_ACTIVITY_COUNT,
                    ASK_LAST_ACTIVITY, PLAN_TRIP, PLAN_PACKING_LIST, LIST_PACKING_PREFERENCES,
                    SHOW_TRAVEL_ITINERARY_DRAFT,
                    ASK_LAST_PURCHASE, ASK_PRICE_SUMMARY, ASK_EXPENSE_HISTORY,
                    ASK_PAYMENT_HISTORY,
                    ASK_VENUE_VISIT_INFO,
                    ASK_FREQUENT_STORE, ASK_INVENTORY_EXTREMES,
                    CHECK_SHOPPING_INVENTORY, LIST_UNPLACED_ITEMS,
                    ASK_ITEM_KNOWLEDGE_SUMMARY, ASK_SCHEDULE_REMINDER,
                    ASK_SCHEDULE_INFO, ASK_PRICE_HISTORY, ASK_PLACE, ASK_TASK_PLACE,
                    LIST_TASKS, LIST_SCHEDULES, SUGGEST_NEARBY, BOOK_RESTAURANT,
                    UNKNOWN -> false;
            default -> true;
        };
    }

    private static final class MutationBoundary {

        private final Runnable beforeMutation;
        private boolean entered;

        private MutationBoundary(Runnable beforeMutation) {
            this.beforeMutation = java.util.Objects.requireNonNull(
                    beforeMutation, "beforeMutation is required");
        }

        private void before(IntentCommand command) {
            if (command == null || isPotentiallyMutating(command.type())) {
                beforeMutation();
            }
        }

        private void beforeMutation() {
            if (entered) {
                return;
            }
            beforeMutation.run();
            entered = true;
        }
    }

    /** 地點名稱解析:先精確比對,再包含比對(規則式;不讓 LLM 決定 id)。 */
    private Optional<Place> resolvePlace(String placeName) {
        return placeAliasService.resolve(placeName);
    }

    private IntentResult placeInfo(Place place) {
        String guidance = familyMessageService == null ? null
                : familyMessageService.placeGuidance(place.getName()).orElse(null);
        return IntentResult.placeInfo(place, guidance);
    }

    private Optional<IntentResult> answerKnownPlaceQuestion(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String candidate = text.strip()
                .replaceFirst("[?？]+$", "")
                .replaceFirst("^你知道", "")
                .replaceFirst("在哪(?:裡|裏|兒)?(?:嗎)?$", "")
                .strip();
        if (candidate.equals(text.strip()) || candidate.isBlank() || candidate.length() > 100) {
            return Optional.empty();
        }
        return resolvePlace(candidate).map(this::placeInfo);
    }

}
