package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
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
    private com.aproject.aidriven.mymobilesecretary.utility.application.UtilityBillService
            utilityBillService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.tag.application
                    .TaggedRecordConversationService
            taggedRecordConversationService;
    private PurchaseConversationService purchaseConversationService;
    private PlanningItemTypeAnswerService planningItemTypeAnswerService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.application.KnowledgeRecordDeletionService
            knowledgeRecordDeletionService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.application.KnowledgeRecordEditingService
            knowledgeRecordEditingService;
    private com.aproject.aidriven.mymobilesecretary.knowledge.application
                    .ProductDraftCompletionConversationService
            productDraftCompletionService;
    private com.aproject.aidriven.mymobilesecretary.venue.application.VenueVisitInformationService
            venueVisitInformationService;

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
        MutationBoundary mutationBoundary = new MutationBoundary(beforeMutation);
        IntentResult result = null;
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(requestId);
             IntentInterpreterTelemetryContext.Scope telemetryScope =
                     IntentInterpreterTelemetryContext.open()) {
            CapabilityShadowObservation shadowObservation =
                    CapabilityShadowObservation.observe(capabilityShadowRouter, effectiveText);
            try {
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
                recordLifeUtteranceSafely(userText, result);
                recordIssueIfUnresolved(userText, result);
                conversationContextService.rememberExchange(userText, result);
                return result;
            } catch (RuntimeException exception) {
                flowTrace.unexpectedFailure();
                throw exception;
            } finally {
                recordDecisionTraceSafely(requestId, channel, userText, result, flowTrace,
                        telemetryScope.snapshot(), shadowObservation, startedNanos);
            }
        }
    }

    private void recordLifeUtteranceSafely(String text, IntentResult result) {
        if (universalLifeRecordService == null) return;
        try {
            universalLifeRecordService.recordUtterance(text, result);
        } catch (RuntimeException exception) {
            log.warn("Universal life-record tagging failed ({})",
                    exception.getClass().getSimpleName());
        }
    }

    private IntentResult doHandle(String text, String interpretationText, IntentFlowTrace flowTrace,
                                  MutationBoundary mutationBoundary) {
        // 產品更正必須先於所有 pending draft，否則「你沒聽懂這個草稿」會被草稿狀態機消耗。
        Optional<IntentResult> productFeedback = ProductFeedbackBoundary.answer(text);
        if (productFeedback.isPresent()) {
            return productFeedback.get();
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
            Optional<IntentResult> taggedRecord = taggedRecordConversationService.answer(text);
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
        Optional<IntentResult> itineraryDraft = travelItineraryDraftAnswerService.answer(
                text, mutationBoundary::beforeMutation);
        if (itineraryDraft.isPresent()) {
            return itineraryDraft.get();
        }
        Optional<IntentResult> activityCount = activityCountAnswerService.answer(text);
        if (activityCount.isPresent()) {
            return activityCount.get();
        }
        Optional<IntentResult> lastActivity = lastActivityAnswerService.answer(text);
        if (lastActivity.isPresent()) {
            return lastActivity.get();
        }
        Optional<IntentResult> packing = travelPackingAnswerService.answer(
                text, mutationBoundary::beforeMutation);
        if (packing.isPresent()) {
            return packing.get();
        }
        Optional<IntentResult> travelPlanning = travelPlanningIntakeService.answer(text);
        if (travelPlanning.isPresent()) {
            return travelPlanning.get();
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
        Optional<LocalDate> overviewDate = dailyScheduleDate(text, clock);
        if (overviewDate.isPresent()) {
            return dailyScheduleOverviewService.overview(overviewDate.get());
        }
        // 拒絕必須先於確認判斷:「不要併入固定行程」也包含「併入固定行程」字樣
        if (isScheduleMergeRejection(text)) {
            return dailyScheduleOverviewService.rejectMerge(text);
        }
        if (isScheduleMergeConfirmation(text)) {
            mutationBoundary.beforeMutation();
            return dailyScheduleOverviewService.confirmMerge();
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
        Optional<String> schoolPickupQuestion = schoolPickupClarification(text);
        boolean deferPickupClarification = schoolPickupQuestion.isPresent()
                && hasIndependentIntentBesidesSchoolDropOff(text);
        if (schoolPickupQuestion.isPresent() && !deferPickupClarification) {
            return IntentResult.clarificationNeeded(schoolPickupQuestion.get());
        }
        Optional<String> help = capabilityHelp(text);
        if (help.isPresent()) {
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, help.get());
        }
        Optional<IntentResult> knownPlace = answerKnownPlaceQuestion(text);
        if (knownPlace.isPresent()) {
            return knownPlace.get();
        }
        IntentScript script;
        IntentInterpreter interpreter = interpreterProvider.getIfAvailable();
        if (interpreter == null) {
            flowTrace.validationFailed("INTERPRETER_NOT_CONFIGURED");
            return safeFallback(text, "意圖解析未啟用", mutationBoundary);
        }
        try {
            script = interpreter.interpret(interpretationText, Instant.now(clock),
                    conversationContextService.snapshot());
            script = IntentScriptSafetyPolicy.apply(text, script, clock);
            script = IntentScriptDateRangePolicy.apply(text, script, Instant.now(clock));
        } catch (Exception e) {
            log.warn("Intent interpretation failed ({}); applying safe fallback",
                    e.getClass().getSimpleName());
            flowTrace.validationFailed("INTERPRETER_FAILURE");
            return safeFallback(text, "AI 暫時無法使用", mutationBoundary);
        }
        if (script == null || script.commands() == null || script.commands().isEmpty()) {
            flowTrace.validationFailed("EMPTY_INTERPRETATION");
            return safeFallback(text, "解析結果是空的", mutationBoundary);
        }
        if (deferPickupClarification) {
            script = applySchoolPickupSafeguard(script, schoolPickupQuestion.orElseThrow());
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
        return Optional.empty();
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
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        boolean asking = normalized.contains("能力範圍")
                || normalized.contains("功能介紹")
                || normalized.contains("你會什麼")
                || normalized.contains("你能做什麼")
                || normalized.contains("你可以做什麼");
        if (!asking) return Optional.empty();
        return Optional.of("""
                我目前可以直接幫你：
                1. 建立、修改、完成與查詢待辦，包含期限、優先級、分類、固定提醒。
                2. 建立與調整行程，檢查撞期、交通可行性、空檔、提醒與每週固定行程。
                3. 管理地點與到達／離開提醒，整理順路事項。
                4. 管理購物清單、已盤點庫存、品項店家與歷史單價。
                5. 設定勿擾、天氣條件提醒與交通監看。
                你也可以直接描述生活安排；資訊不足時我會先問，不會自行確認有衝突的行程。
                """.strip());
    }

    static Optional<String> recurringRoutineClarification(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        boolean routine = normalized.contains("每個上班日")
                && (normalized.contains("日常行程") || normalized.contains("日常安排"))
                && (normalized.contains("上班") || normalized.contains("通勤"));
        if (!routine) return Optional.empty();
        return Optional.of("""
                我知道你要記的是「上班日固定生活時段」，而且親自到場的事情不能排進通勤與上班區間；我不會把這段當成一般回饋，也不會先猜時間建立。
                建立前請一次確認這 4 點：
                1. 上班日固定是週一到週五嗎？國定假日是否略過？
                2. 送完小孩後，最晚幾點要到富邦內湖大樓？
                3. 「18:10–20 下班」是每天可在這段時間任選，還是不同星期有固定下班時間？
                4. 要封鎖的是 07:00 起床到抵達公司、上班到回家全程，還是只封鎖搭車／騎車區段？
                你回答後，我再拆成固定時段；下雨與未下雨路線會保留為條件資訊，不會同時占兩份時間。
                """.strip());
    }

    /**
     * 「送孩子上課」通常還隱含下課接回，但接的人不一定是使用者。沒有交代接回分工時，
     * 必須先確認，不能把整段課程當成使用者被占用，也不能自行發明接送緩衝時間。
     */
    static Optional<String> schoolPickupClarification(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        boolean child = normalized.contains("女兒") || normalized.contains("兒子")
                || normalized.contains("小孩") || normalized.contains("孩子");
        boolean classTrip = normalized.contains("送") && child
                && (normalized.contains("課") || normalized.contains("補習")
                || normalized.contains("安親班"));
        boolean scheduling = normalized.matches(".*(?:今天|明天|明日|後天|每週|每周|週[一二三四五六日天]|"
                + "星期[一二三四五六日天]|禮拜[一二三四五六日天]|\\d{1,2}(?::\\d{2}|點)).*");
        boolean pickupUncertain = containsAny(normalized,
                "還不知道誰接", "不知道誰接", "誰接還不知道", "接的人還沒決定",
                "接送人未定", "接送人待確認", "還沒確定誰接", "接的人不確定")
                || (containsAny(normalized, "可能", "不一定", "未定", "待確認", "還沒確認")
                && containsAny(normalized, "接", "校車", "送回", "回家"));
        boolean pickupSpecified = !pickupUncertain && (normalized.contains("接")
                || normalized.contains("校車") || normalized.contains("自己回")
                || normalized.contains("自行回") || normalized.contains("自己搭")
                || normalized.contains("自行搭") || normalized.contains("不用接")
                || normalized.contains("不必接"));
        if (!classTrip || !scheduling || pickupSpecified) {
            return Optional.empty();
        }
        return Optional.of("我理解送孩子去上課後通常還有下課接回，但接的人不一定是你。"
                + "請確認誰送、誰接，以及接回時間和地點；確認前我不會建立行程或自行假設接送時間。");
    }

    static boolean hasIndependentIntentBesidesSchoolDropOff(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return containsAny(normalized,
                "會議", "開會", "聚餐", "吃飯", "回診", "看醫生", "看牙", "牙醫",
                "復健", "上班", "報告", "簡報", "買菜", "採買", "購物", "郵局",
                "銀行", "運動", "健身", "提醒", "待辦", "繳費", "付款", "取貨");
    }

    static IntentScript applySchoolPickupSafeguard(IntentScript script, String question) {
        java.util.List<IntentCommand> safeCommands = new java.util.ArrayList<>();
        boolean alreadyAsksAboutPickup = false;
        for (IntentCommand command : script.commands()) {
            if (command == null || command.type() == null) continue;
            if (isUnsafeSchoolPickupMutation(command)) continue;
            safeCommands.add(command);
            if (command.type() == IntentCommand.Type.UNKNOWN
                    && containsAny(command.reason() == null ? "" : command.reason(),
                    "誰接", "由誰接", "接送人", "下課後", "校車")) {
                alreadyAsksAboutPickup = true;
            }
        }
        if (!alreadyAsksAboutPickup) {
            safeCommands.add(new IntentCommand(IntentCommand.Type.UNKNOWN,
                    null, null, null, null, null, null, question,
                    null, null, null, null, null));
        }
        return new IntentScript(java.util.List.copyOf(safeCommands));
    }

    private static boolean isUnsafeSchoolPickupMutation(IntentCommand command) {
        if (command == null || command.type() == null) return false;
        boolean scheduleMutation = switch (command.type()) {
            case CREATE_SCHEDULE, CREATE_RELATIVE_SCHEDULE, RESCHEDULE_SCHEDULE,
                    SET_SCHEDULE_RECURRING -> true;
            default -> false;
        };
        if (!scheduleMutation) return false;
        String title = command.title() == null ? "" : command.title().replaceAll("\\s+", "");
        return containsAny(title, "女兒", "兒子", "孩子", "小孩", "送", "上課", "英文課",
                "安親班", "才藝課", "補習");
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
                    command.reason() == null || command.reason().isBlank()
                            ? "我沒聽懂,可以換個說法嗎?" : command.reason());
        }
        return intentHandlerRegistry.dispatch(text, command);
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
