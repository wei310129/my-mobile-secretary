package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.intent.capability.routing.CapabilityShadowRouter;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTraceDraft;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService.OutcomeRecorded;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.OutcomeReason;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
    private final ScheduleService scheduleService;
    private final ScheduleFollowUpService followUpService;
    private final IntentIssueService issueService;
    private final com.aproject.aidriven.mymobilesecretary.planner.application.NearbySuggestionService nearbySuggestionService;
    private final PlaceRepository placeRepository;
    private final com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService placeService;
    private final com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService geofenceRuleService;
    private final com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService priceRecordService;
    private final LifestyleIntentService lifestyleIntentService;
    private final DailyScheduleOverviewService dailyScheduleOverviewService;
    private final ReminderTimingAnswerService reminderTimingAnswerService;
    private final LastActivityAnswerService lastActivityAnswerService;
    private final ActivityCountAnswerService activityCountAnswerService;
    private final TravelPlanningIntakeService travelPlanningIntakeService;
    private final TravelPackingAnswerService travelPackingAnswerService;
    private final TravelItineraryDraftAnswerService travelItineraryDraftAnswerService;
    private final ScheduleTaskConflictAnswerService scheduleTaskConflictAnswerService;
    private final TaskDetailAnswerService taskDetailAnswerService;
    private final RestaurantBookingService restaurantBookingService;
    private final BulkScheduleCancellationService bulkScheduleCancellationService;
    private final DelegatedDecisionService delegatedDecisionService;
    private final ConversationContextService conversationContextService;
    private final com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService placeAliasService;
    private final int bindRadiusMeters;
    private final Clock clock;
    private IntentDecisionTraceService decisionTraceService;
    private CapabilityShadowRouter capabilityShadowRouter;
    private com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService
            familyMessageService;
    private com.aproject.aidriven.mymobilesecretary.family.application.FamilyPersonService
            familyPersonService;

    public IntentService(ObjectProvider<IntentInterpreter> interpreterProvider,
                         TaskService taskService,
                         ScheduleService scheduleService,
                         ScheduleFollowUpService followUpService,
                         IntentIssueService issueService,
                         com.aproject.aidriven.mymobilesecretary.planner.application.NearbySuggestionService nearbySuggestionService,
                         PlaceRepository placeRepository,
                         com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService placeService,
                         com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService geofenceRuleService,
                         com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService priceRecordService,
                         LifestyleIntentService lifestyleIntentService,
                         DailyScheduleOverviewService dailyScheduleOverviewService,
                         ReminderTimingAnswerService reminderTimingAnswerService,
                         LastActivityAnswerService lastActivityAnswerService,
                         ActivityCountAnswerService activityCountAnswerService,
                         TravelPlanningIntakeService travelPlanningIntakeService,
                         TravelPackingAnswerService travelPackingAnswerService,
                         TravelItineraryDraftAnswerService travelItineraryDraftAnswerService,
                         ScheduleTaskConflictAnswerService scheduleTaskConflictAnswerService,
                         TaskDetailAnswerService taskDetailAnswerService,
                         RestaurantBookingService restaurantBookingService,
                         BulkScheduleCancellationService bulkScheduleCancellationService,
                         DelegatedDecisionService delegatedDecisionService,
                         ConversationContextService conversationContextService,
                         com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService placeAliasService,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${app.knowledge.auto-bind-radius-meters:200}") int bindRadiusMeters,
                         Clock clock) {
        this.interpreterProvider = interpreterProvider;
        this.taskService = taskService;
        this.scheduleService = scheduleService;
        this.followUpService = followUpService;
        this.issueService = issueService;
        this.nearbySuggestionService = nearbySuggestionService;
        this.placeRepository = placeRepository;
        this.placeService = placeService;
        this.geofenceRuleService = geofenceRuleService;
        this.priceRecordService = priceRecordService;
        this.lifestyleIntentService = lifestyleIntentService;
        this.dailyScheduleOverviewService = dailyScheduleOverviewService;
        this.reminderTimingAnswerService = reminderTimingAnswerService;
        this.lastActivityAnswerService = lastActivityAnswerService;
        this.activityCountAnswerService = activityCountAnswerService;
        this.travelPlanningIntakeService = travelPlanningIntakeService;
        this.travelPackingAnswerService = travelPackingAnswerService;
        this.travelItineraryDraftAnswerService = travelItineraryDraftAnswerService;
        this.scheduleTaskConflictAnswerService = scheduleTaskConflictAnswerService;
        this.taskDetailAnswerService = taskDetailAnswerService;
        this.restaurantBookingService = restaurantBookingService;
        this.bulkScheduleCancellationService = bulkScheduleCancellationService;
        this.delegatedDecisionService = delegatedDecisionService;
        this.conversationContextService = conversationContextService;
        this.placeAliasService = placeAliasService;
        this.bindRadiusMeters = bindRadiusMeters;
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
        UUID requestId = RequestCorrelationContext.currentId();
        long startedNanos = System.nanoTime();
        IntentFlowTrace flowTrace = new IntentFlowTrace();
        MutationBoundary mutationBoundary = new MutationBoundary(beforeMutation);
        IntentResult result = null;
        try (RequestCorrelationContext.Scope ignored = RequestCorrelationContext.open(requestId);
             IntentInterpreterTelemetryContext.Scope telemetryScope =
                     IntentInterpreterTelemetryContext.open()) {
            CapabilityShadowObservation shadowObservation =
                    CapabilityShadowObservation.observe(capabilityShadowRouter, text);
            try {
                // 場合祝賀在記錄之前套用:意圖問題與上下文都要記使用者實際看到的回覆
                result = OccasionGreeting.decorate(text,
                        doHandle(text, flowTrace, mutationBoundary));
                flowTrace.complete(result);
                recordIssueIfUnresolved(text, result);
                conversationContextService.rememberExchange(text, result);
                return result;
            } catch (RuntimeException exception) {
                flowTrace.unexpectedFailure();
                throw exception;
            } finally {
                recordDecisionTraceSafely(requestId, channel, text, result, flowTrace,
                        telemetryScope.snapshot(), shadowObservation, startedNanos);
            }
        }
    }

    private IntentResult doHandle(String text, IntentFlowTrace flowTrace,
                                  MutationBoundary mutationBoundary) {
        Optional<IntentResult> productFeedback = ProductFeedbackBoundary.answer(text);
        if (productFeedback.isPresent()) {
            return productFeedback.get();
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
            script = interpreter.interpret(text, Instant.now(clock), conversationContextService.snapshot());
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

    /** 依驗證後的 command 執行;LLM 輸出一律先驗證再信。 */
    private IntentResult execute(String text, IntentCommand command) {
        if (command == null || command.type() == null) {
            throw new IllegalArgumentException("missing type");
        }
        // 模糊時間語(「下班後」「週末」)不可被猜成具體時間直接寫入:
        // 一律建議+回問(使用者 2026-07-16 裁決,見 VagueTimeGuard)
        Optional<IntentResult> vagueTime = VagueTimeGuard.clarify(text, command);
        if (vagueTime.isPresent()) {
            return vagueTime.get();
        }
        return switch (command.type()) {
            case EXPLAIN_LAST_FAILURE -> FailureExplanationService.answer(
                            text, conversationContextService.snapshot())
                    .orElseGet(() -> IntentResult.message(IntentResult.Action.FAILURE_EXPLAINED,
                            "目前沒有可追查的上一筆解析失敗紀錄。"));
            case ADD_SCHEDULE_REMINDER, SUGGEST_FREE_SLOT, CREATE_RELATIVE_SCHEDULE,
                    LIST_AGENDA, ASK_TASK_INFO, ASK_AVAILABILITY, LIST_RECENT,
                    SUGGEST_ROUTE_TASKS, SET_PLACE_ALIAS, ADD_SHOPPING_ITEMS,
                    REMOVE_SHOPPING_ITEM, LIST_SHOPPING_ITEMS, SET_INVENTORY,
                    ASK_PRICE_COMPARISON, ASK_WEATHER, CREATE_WEATHER_REMINDER,
                    ASK_TRAVEL_TIME, ASK_DEPARTURE_TIME, CREATE_TRAFFIC_WATCH,
                    CHECK_FEASIBILITY, SET_PLANNING_BUFFER, ACCEPT_CONTEXT,
                    SHIFT_CONTEXT_LATER, CANCEL_CONTEXT, SET_CONTEXT_PLACE,
                    COPY_CONTEXT, SOCIAL, UPDATE_TASK, PAUSE_RECURRING_TASK,
                    RESUME_RECURRING_TASK, SKIP_RECURRING_OCCURRENCE,
                    LIST_COMPLETED_TASKS, MARK_SHOPPING_PURCHASED,
                    CLEAR_SHOPPING_LIST, LIST_SHOPPING_BY_PLACE,
                    AGENDA_SUMMARY, RESIZE_SCHEDULE, ADJUST_INVENTORY,
                    LIST_INVENTORY, ASK_ITEM_PLACES, BIND_ITEM_PLACE,
                    LIST_ITEMS_BY_PLACE, GROUP_SHOPPING_BY_PLACE,
                    RESTOCK_LOW_INVENTORY, SET_QUIET_HOURS, CLEAR_QUIET_HOURS,
                    MUTE_REMINDERS, RESUME_REMINDERS,
                    ASK_REMINDER_PREFERENCES, LIST_LOCATION_TASKS,
                    ASK_PLACE_TASKS, ASK_TASK_GEOFENCE, UPDATE_TASK_GEOFENCE,
                    REMOVE_TASK_PLACE, ASK_NEXT_SCHEDULE, ASK_SCHEDULE_GAP,
                    GROUP_SCHEDULES_BY_DAY,
                    CHECK_SCHEDULE_CONFLICTS, SUGGEST_NEXT_TASK,
                    GROUP_TASKS_BY_CATEGORY,
                    ASK_TASK_PROGRESS, GROUP_TASKS_BY_DUE,
                    ASK_TASK_LOAD, ASK_BUSY_TASK_DAY, ASK_BUSY_SCHEDULE_DAY,
                    ASK_LONGEST_SCHEDULE,
                    GROUP_SCHEDULES_BY_PLACE, ASK_LAST_PURCHASE,
                    ASK_PRICE_SUMMARY,
                    ASK_FREQUENT_STORE, ASK_INVENTORY_EXTREMES,
                    CHECK_SHOPPING_INVENTORY, LIST_UNPLACED_ITEMS,
                    ASK_ITEM_KNOWLEDGE_SUMMARY,
                    ASK_SCHEDULE_REMINDER -> lifestyleIntentService.execute(text, command);
            case CREATE_TASK -> {
                requireText(command.title(), "title");
                // 防重複(使用者實際踩過:「拿包裹」被建了兩次):同名未結案任務存在就回問
                boolean duplicate = taskService.findOpenTasksMatching(command.title()).stream()
                        .anyMatch(t -> t.getTitle().equalsIgnoreCase(command.title().strip()));
                if (duplicate) {
                    yield IntentResult.clarificationNeeded(
                            "已經有「%s」這件未完成待辦了,不再重複建立;要改時間或地點直接說。"
                                    .formatted(command.title()));
                }
                IntentOptions options = command.safeOptions();
                Task task = taskService.createTask(
                        command.title(), null, parsePriority(command.priority()),
                        parseTime(command.dueAt()), parseCategory(options.category()),
                        parseRecurrence(options.recurrence()), parseCondition(options.condition()), null);
                Place taskPlace = null;
                if (command.placeName() != null && !command.placeName().isBlank()) {
                    taskPlace = resolvePlace(command.placeName())
                            .orElseGet(() -> placeService.createPlace(command.placeName(), null, null, null, null));
                    var trigger = parseTrigger(options.triggerType());
                    if (!geofenceRuleService.ruleExists(task.getId(), taskPlace.getId(), trigger)) {
                        geofenceRuleService.createRule(task.getId(), taskPlace.getId(),
                                positive(options.radiusMeters(), bindRadiusMeters), trigger);
                    }
                }
                yield IntentResult.taskCreated(task, options.clarificationQuestion(), taskPlace);
            }
            case CREATE_SCHEDULE -> {
                requireText(command.title(), "title");
                Instant startAt = parseTime(command.startAt());
                Instant endAt = parseTime(command.endAt());
                if (startAt == null) {
                    throw new IllegalArgumentException("schedule missing startAt");
                }
                // 只有一個明確時點、沒有結束時間的生活事項(如「今晚十點倒垃圾」)
                // 是 timed task，不應因 LLM 偶爾誤選 CREATE_SCHEDULE 而整句失敗。
                if (endAt == null) {
                    yield execute(text, new IntentCommand(IntentCommand.Type.CREATE_TASK,
                            command.title(), command.startAt(), null, null,
                            command.placeName(), command.priority(), command.reason(),
                            null, null, null, null, false, command.options()));
                }
                Long placeId = resolvePlace(command.placeName()).map(Place::getId).orElse(null);
                ScheduleDecision decision = scheduleService.createSchedule(
                        command.title(), startAt, endAt, placeId,
                        parseScheduleRecurrence(command), parseRecurrenceUntil(command));
                yield IntentResult.scheduleDecided(decision);
            }
            case COMPLETE_TASK -> {
                TaskMatch match = matchOpenTask(command, "劃");
                yield match.failure() != null ? match.failure()
                        : IntentResult.taskCompleted(taskService.confirmTask(match.task().getId()));
            }
            case CANCEL_TASK -> {
                TaskMatch match = matchOpenTask(command, "取消");
                yield match.failure() != null ? match.failure()
                        : IntentResult.taskCanceled(taskService.cancelTask(match.task().getId()));
            }
            case CANCEL_ALL_TASKS -> IntentResult.allTasksCanceled(taskService.cancelAllOpenTasks());
            case RESCHEDULE_TASK -> {
                Instant newDueAt = parseTime(command.dueAt());
                if (newDueAt == null) {
                    throw new IllegalArgumentException("reschedule missing dueAt");
                }
                TaskMatch match = matchOpenTask(command, "改");
                yield match.failure() != null ? match.failure()
                        : IntentResult.taskRescheduled(taskService.changeDueDate(match.task().getId(), newDueAt));
            }
            case CANCEL_SCHEDULE -> {
                ScheduleMatch match = matchCancelableSchedule(command, "取消");
                yield match.failure() != null ? match.failure()
                        : IntentResult.scheduleCanceled(scheduleService.cancelSchedule(match.item().getId()));
            }
            case RESCHEDULE_SCHEDULE -> {
                Instant newStartAt = parseTime(command.startAt());
                if (newStartAt == null) {
                    throw new IllegalArgumentException("schedule reschedule missing startAt");
                }
                ScheduleMatch match = matchReschedulableSchedule(command, "改");
                if (match.failure() != null) {
                    yield match.failure();
                }
                Instant newEndAt = parseTime(command.endAt());
                if (newEndAt == null) {
                    newEndAt = newStartAt.plus(java.time.Duration.between(
                            match.item().getStartAt(), match.item().getEndAt()));
                }
                String recurrenceScope = command.safeOptions().recurrenceScope();
                if (match.item().getRecurrence() != ScheduleItem.Recurrence.NONE) {
                    if (recurrenceScope == null || recurrenceScope.isBlank()) {
                        yield IntentResult.clarificationNeeded(
                                "「%s」是固定行程。這次改時間要只改本次,還是之後每一次都改?"
                                        .formatted(match.item().getTitle()));
                    }
                    if ("THIS_OCCURRENCE".equalsIgnoreCase(recurrenceScope)) {
                        yield IntentResult.scheduleOccurrenceRescheduled(
                                scheduleService.rescheduleSingleOccurrence(
                                        match.item().getId(), newStartAt, newEndAt));
                    }
                    if (!"SERIES".equalsIgnoreCase(recurrenceScope)) {
                        yield IntentResult.clarificationNeeded(
                                "請確認要只改這一次,還是修改整個固定系列。");
                    }
                }
                ScheduleDecision decision = scheduleService.reschedule(
                        match.item().getId(), newStartAt, newEndAt);
                yield IntentResult.scheduleRescheduled(decision);
            }
            case SET_SCHEDULE_RECURRING -> {
                requireText(command.title(), "title");
                boolean recurring = !Boolean.FALSE.equals(command.recurring());
                ScheduleMatch match = matchReschedulableSchedule(command.title(), "設定");
                yield match.failure() != null ? match.failure()
                        : IntentResult.scheduleRecurrenceSet(
                                scheduleService.setRecurrence(match.item().getId(), recurring
                                        ? parseScheduleRecurrence(command)
                                        : ScheduleItem.Recurrence.NONE,
                                        recurring ? parseRecurrenceUntil(command) : null));
            }
            case ASK_SCHEDULE_INFO -> {
                requireText(command.title(), "title");
                ScheduleMatch match = matchReschedulableSchedule(command.title(), "查");
                yield match.failure() != null ? match.failure()
                        : IntentResult.scheduleInfo(match.item(),
                                match.item().getPlaceId() == null
                                        ? null : placeService.getPlace(match.item().getPlaceId()));
            }
            case ASK_PRICE_HISTORY -> {
                requireText(command.title(), "title");
                yield IntentResult.priceHistory(command.title(),
                        priceRecordService.list(command.title()));
            }
            case ASK_LAST_ACTIVITY -> {
                requireText(command.title(), "title");
                yield lastActivityAnswerService.answerTopic(
                        command.title(), command.placeName(), command.safeOptions().filter());
            }
            case ASK_ACTIVITY_COUNT -> {
                requireText(command.title(), "title");
                yield activityCountAnswerService.answerTopic(
                        command.title(), command.safeOptions().filter());
            }
            case PLAN_TRIP -> travelPlanningIntakeService.intake(text);
            case PLAN_PACKING_LIST -> travelPackingAnswerService.draft(text);
            case LIST_PACKING_PREFERENCES -> travelPackingAnswerService.listPreferences();
            case SET_PACKING_PREFERENCE -> {
                requireText(command.title(), "title");
                yield travelPackingAnswerService.setPreference(
                        command.title(), command.safeOptions().filter(), command.reason());
            }
            case SHOW_TRAVEL_ITINERARY_DRAFT -> travelItineraryDraftAnswerService.showLatest();
            case CONFIRM_TRAVEL_ITINERARY_DRAFT ->
                    travelItineraryDraftAnswerService.confirmLatest();
            case DISCARD_TRAVEL_ITINERARY_DRAFT ->
                    travelItineraryDraftAnswerService.discardLatest();
            case LIST_SCHEDULES_ON_DATE -> {
                Instant dateTime = parseTime(command.startAt());
                if (dateTime == null) {
                    throw new IllegalArgumentException("daily schedule query missing startAt");
                }
                yield dailyScheduleOverviewService.overview(
                        LocalDate.ofInstant(dateTime, ZoneId.of("Asia/Taipei")));
            }
            case ASK_PLACE -> {
                requireText(command.placeName(), "placeName");
                yield resolvePlace(command.placeName())
                        .map(this::placeInfo)
                        .orElseGet(() -> IntentResult.clarificationNeeded(
                                "我沒有叫「%s」的地點紀錄,說「建立地點:%s」我就去 Google 查來存。"
                                        .formatted(command.placeName(), command.placeName())));
            }
            case CREATE_PLACE -> {
                requireText(command.placeName(), "placeName");
                // 已有同名(含)地點就不重建,直接回資訊
                var existing = resolvePlace(command.placeName());
                if (existing.isPresent()) {
                    yield placeInfo(existing.get());
                }
                // 座標留空 → PlaceService 向 Google 補全;查不到丟業務錯誤,由外層轉成回覆
                yield IntentResult.placeCreated(placeService.createPlace(
                        command.placeName(), null, null, null, null));
            }
            case BIND_TASK_PLACE -> {
                requireText(command.placeName(), "placeName");
                TaskMatch match = matchOpenTask(command, "綁");
                if (match.failure() != null) {
                    yield match.failure();
                }
                // 地點沒建過就先透過 Google 建檔,再綁——使用者只講一句就要完成閉環
                Place place = resolvePlace(command.placeName())
                        .orElseGet(() -> placeService.createPlace(
                                command.placeName(), null, null, null, null));
                var trigger = parseTrigger(command.safeOptions().triggerType());
                if (!geofenceRuleService.ruleExists(match.task().getId(), place.getId(), trigger)) {
                    geofenceRuleService.createRule(match.task().getId(), place.getId(),
                            positive(command.safeOptions().radiusMeters(), bindRadiusMeters), trigger);
                }
                yield IntentResult.taskPlaceBound(match.task(), place);
            }
            case ASK_TASK_PLACE -> {
                TaskMatch match = matchOpenTask(command, "查");
                if (match.failure() != null) {
                    yield match.failure();
                }
                var places = geofenceRuleService.listRulesForTask(match.task().getId()).stream()
                        .map(rule -> placeService.getPlace(rule.getPlaceId()))
                        .distinct()
                        .toList();
                yield IntentResult.taskPlaceInfo(match.task(), places);
            }
            case FEEDBACK -> handleFeedback(command);
            case LIST_TASKS -> {
                var open = taskService.listOpenTasks();
                yield lifestyleIntentService.listTasks(command, taskListAdvice(open));
            }
            case LIST_SCHEDULES -> lifestyleIntentService.listSchedules(command);
            case SUGGEST_NEARBY -> {
                // 使用者 2026-07-15 更正:沒明講多久就不能直接認定——先回問,同時附預設時窗的參考
                if (command.windowHours() != null && command.windowHours() > 0) {
                    yield IntentResult.suggestionMade(nearbySuggestionService.suggest(
                            java.time.Duration.ofHours(command.windowHours())));
                }
                long defaultHours = nearbySuggestionService.defaultWindow().toHours();
                yield IntentResult.suggestionMade(
                        "「待會」你抓多久?跟我說「看2小時」我就用那個範圍重算;先用 %d 小時給你參考:\n\n%s"
                                .formatted(defaultHours, nearbySuggestionService.suggest()));
            }
            case RECORD_OUTCOME -> {
                if (command.onTime() == null) {
                    throw new IllegalArgumentException("outcome missing onTime");
                }
                boolean onTime = command.onTime();
                if (!onTime && (command.overrunMinutes() == null || command.overrunMinutes() <= 0)) {
                    throw new IllegalArgumentException("overrun outcome missing overrunMinutes");
                }
                // 原文整句存進 note:分類選項有限,細節(「客戶臨時加需求」)不能丟
                Optional<OutcomeRecorded> recorded = followUpService.recordOutcomeForLatestAsked(
                        onTime, command.overrunMinutes(), parseOutcomeReason(command.outcomeReason()), text);
                yield recorded.map(IntentResult::outcomeRecorded)
                        .orElseGet(() -> IntentResult.clarificationNeeded(
                                "最近沒有等待回報的行程,想回報哪一個行程的結果?"));
            }
            // 超能力範圍的要求不可只說做不到,要走替代引導(使用者裁決 #47)
            case BOOK_RESTAURANT -> restaurantBookingService.handle(text, command);
            // 破壞性操作安全閘:只刪指定範圍內的非固定行程(使用者裁決 #49)
            case BULK_CANCEL_SCHEDULES -> bulkScheduleCancellationService.cancelWithin(
                    parseTime(command.startAt()), parseTime(command.endAt()));
            case UNKNOWN -> IntentResult.clarificationNeeded(
                    command.reason() == null || command.reason().isBlank()
                            ? "我沒聽懂,可以換個說法嗎?" : command.reason());
        };
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
     * 清單附加建議(使用者 2026-07-15 要求):
     * 1. 缺期限的待辦追問時間地點——沒有期限與地點就只能被動等使用者想起。
     * 2. 對最急(最早到期)的待辦給具體時段建議,由使用者核准後再建行程。
     */
    private String taskListAdvice(java.util.List<Task> tasks) {
        StringBuilder advice = new StringBuilder();
        Instant now = Instant.now(clock);

        var overdue = tasks.stream()
                .filter(t -> t.getDueAt() != null && t.getDueAt().isBefore(now))
                .limit(3).toList();
        if (!overdue.isEmpty()) {
            advice.append("\n\n已逾期:\n%s\n\n建議直接說「%s改到明天下午三點」，或告訴我已完成。"
                    .formatted(overdue.stream().map(t -> "「" + t.getTitle() + "」")
                                    .collect(java.util.stream.Collectors.joining("\n")),
                            overdue.getFirst().getTitle()));
        }

        var missing = tasks.stream().filter(t -> t.getDueAt() == null).limit(3).toList();
        if (!missing.isEmpty()) {
            advice.append("\n\n未設定期限:\n%s\n\n建議補一句（例如「%s這週六早上處理」），我才能按時提醒。"
                    .formatted(
                            missing.stream().map(t -> "「" + t.getTitle() + "」")
                                    .collect(java.util.stream.Collectors.joining("\n")),
                            missing.get(0).getTitle()));
        }

        var missingPlace = tasks.stream()
                .filter(t -> looksLocationBased(t.getTitle()))
                .filter(t -> geofenceRuleService.listRulesForTask(t.getId()).isEmpty())
                .limit(3).toList();
        if (!missingPlace.isEmpty()) {
            advice.append("\n\n可能需要地點但尚未設定:\n%s\n\n建議可以說「%s是在蝦皮店到店領」。"
                    .formatted(missingPlace.stream().map(t -> "「" + t.getTitle() + "」")
                                    .collect(java.util.stream.Collectors.joining("\n")),
                            missingPlace.getFirst().getTitle()));
        }

        tasks.stream()
                .filter(t -> t.getDueAt() != null && t.getDueAt().isAfter(now))
                .findFirst()
                .flatMap(t -> suggestSlotBefore(t, now))
                .ifPresent(advice::append);
        return advice.toString();
    }

    static boolean looksLocationBased(String title) {
        if (title == null) return false;
        String value = title.toLowerCase(java.util.Locale.ROOT);
        return java.util.List.of("拿", "領", "取", "買", "送", "寄", "繳", "pickup", "buy", "deliver")
                .stream().anyMatch(value::contains);
    }

    /**
     * 期限前找 30 分鐘空檔:預設抓期限前 2 小時開始,與已確認行程重疊就放棄建議
     * (寧可不建議,不給會撞期的建議)。
     */
    private Optional<String> suggestSlotBefore(Task task, Instant now) {
        Instant start = task.getDueAt().minus(java.time.Duration.ofHours(2));
        if (start.isBefore(now)) {
            start = now.plus(java.time.Duration.ofMinutes(15));
        }
        Instant end = start.plus(java.time.Duration.ofMinutes(30));
        if (end.isAfter(task.getDueAt())) {
            return Optional.empty();
        }
        final Instant s = start;
        boolean overlaps = scheduleService.listSchedules(
                        com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.CONFIRMED)
                .stream()
                .anyMatch(item -> item.getStartAt().isBefore(end) && s.isBefore(item.getEndAt()));
        if (overlaps) {
            return Optional.empty();
        }
        var fmt = java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm");
        var zone = java.time.ZoneId.of("Asia/Taipei");
        return Optional.of("\n\n建議:把「%s」排在 %s-%s(期限前),OK 的話跟我說一聲我就排進行程。"
                .formatted(task.getTitle(),
                        java.time.ZonedDateTime.ofInstant(s, zone).format(fmt),
                        java.time.ZonedDateTime.ofInstant(end, zone)
                                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))));
    }

    /** 接下來的已確認行程:還沒結束的才算(進行中也列),依開始時間排序。 */
    private java.util.List<com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem>
            upcomingConfirmedSchedules() {
        Instant now = Instant.now(clock);
        return scheduleService.listSchedules(
                        com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus.CONFIRMED)
                .stream()
                .filter(item -> item.getEndAt().isAfter(now))
                .toList();
    }

    /**
     * 關鍵字對任務(完成/取消/改期共用):唯一命中才動手,
     * 模糊(多筆)或落空(零筆)都回問,絕不猜——動錯任務比多問一句嚴重。
     */
    private TaskMatch matchOpenTask(String keyword, String actionVerb) {
        var matches = taskService.findOpenTasksMatching(keyword);
        if (matches.isEmpty()) {
            return new TaskMatch(null, IntentResult.clarificationNeeded(
                    "找不到跟「%s」有關的未完成任務。".formatted(keyword)));
        }
        if (matches.size() > 1) {
            conversationContextService.rememberTaskList(matches);
            String titles = java.util.stream.IntStream.range(0, Math.min(matches.size(), 5))
                    .mapToObj(i -> {
                        Task task = matches.get(i);
                        String due = task.getDueAt() == null ? "無期限"
                                : java.time.ZonedDateTime.ofInstant(task.getDueAt(),
                                java.time.ZoneId.of("Asia/Taipei"))
                                .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"));
                        return "%d.「%s」｜%s｜%s".formatted(
                                i + 1, task.getTitle(), due, task.getCategory());
                    })
                    .collect(java.util.stream.Collectors.joining("\n"));
            return new TaskMatch(null, IntentResult.clarificationNeeded(
                    "有 %d 件任務都符合，請回覆編號（例如「第一個」）：\n%s"
                            .formatted(matches.size(), titles)));
        }
        return new TaskMatch(matches.get(0), null);
    }

    /** 有標題走關鍵字;省略標題時以「上一個／清單第 N 個」上下文解析。 */
    private TaskMatch matchOpenTask(IntentCommand command, String actionVerb) {
        if (command.safeOptions().ordinal() != null) {
            Long id = conversationContextService.taskIdAt(command.safeOptions().ordinal());
            if (id != null) {
                return openContextTask(id, actionVerb);
            }
        }
        if (command.title() != null && !command.title().isBlank()) {
            return matchOpenTask(command.title(), actionVerb);
        }
        Long id = conversationContextService.taskIdAt(command.safeOptions().ordinal());
        if (id == null) {
            return new TaskMatch(null, IntentResult.clarificationNeeded(
                    "目前沒有可指代的待辦,請說名稱或先列出待辦。"));
        }
        return openContextTask(id, actionVerb);
    }

    private TaskMatch openContextTask(Long id, String actionVerb) {
        Task task = taskService.getTask(id);
        if (!taskService.listOpenTasks().stream().map(Task::getId).toList().contains(id)) {
            return new TaskMatch(null, IntentResult.clarificationNeeded(
                    "「%s」已經結案,不能再%s。".formatted(task.getTitle(), actionVerb)));
        }
        return new TaskMatch(task, null);
    }

    /** 關鍵字配對結果:task 與 failure 恰有一個非空。 */
    private record TaskMatch(Task task, IntentResult failure) {
    }

    /** 取消只允許已確認或 pending 的行程;同名多筆一定回問。 */
    private ScheduleMatch matchCancelableSchedule(String keyword, String actionVerb) {
        return uniqueScheduleMatch(scheduleService.findCancelableSchedulesMatching(keyword), keyword, actionVerb);
    }

    private ScheduleMatch matchCancelableSchedule(IntentCommand command, String actionVerb) {
        if (command.title() != null && !command.title().isBlank()) {
            return matchCancelableSchedule(command.title(), actionVerb);
        }
        return contextSchedule(command.safeOptions().ordinal(), actionVerb);
    }

    /** 改期可處理 PROPOSED、CONFIRMED 與 PENDING 行程;改後一律重新驗算。 */
    private ScheduleMatch matchReschedulableSchedule(String keyword, String actionVerb) {
        return uniqueScheduleMatch(scheduleService.findReschedulableSchedulesMatching(keyword), keyword, actionVerb);
    }

    private ScheduleMatch matchReschedulableSchedule(IntentCommand command, String actionVerb) {
        if (command.title() != null && !command.title().isBlank()) {
            return matchReschedulableSchedule(command.title(), actionVerb);
        }
        return contextSchedule(command.safeOptions().ordinal(), actionVerb);
    }

    private ScheduleMatch contextSchedule(Integer ordinal, String actionVerb) {
        Long id = conversationContextService.scheduleIdAt(ordinal);
        if (id == null) {
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "目前沒有可指代的行程,請說名稱或先列出行程。"));
        }
        ScheduleItem item = scheduleService.getSchedule(id);
        return new ScheduleMatch(item, null);
    }

    private ScheduleMatch uniqueScheduleMatch(java.util.List<ScheduleItem> matches,
                                              String keyword, String actionVerb) {
        if (matches.isEmpty()) {
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "找不到跟「%s」有關、還能%s的行程。".formatted(keyword, actionVerb)));
        }
        if (matches.size() > 1) {
            String titles = matches.stream().limit(5)
                    .map(item -> "「%s」(%s)".formatted(item.getTitle(),
                            java.time.ZonedDateTime.ofInstant(item.getStartAt(),
                                    java.time.ZoneId.of("Asia/Taipei"))
                                    .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm"))))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "有 %d 個行程都符合:\n%s\n\n請告訴我日期或時間，我才不會%s錯。"
                            .formatted(matches.size(), titles, actionVerb)));
        }
        return new ScheduleMatch(matches.get(0), null);
    }

    /** 行程關鍵字配對結果:item 與 failure 恰有一個非空。 */
    private record ScheduleMatch(ScheduleItem item, IntentResult failure) {
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
                    ASK_LAST_PURCHASE, ASK_PRICE_SUMMARY,
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

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }

    private static Instant parseTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            throw new IllegalArgumentException("bad time: " + iso);
        }
    }

    /** 超時原因寬鬆解析:LLM 給了不認得的值就當沒填,不因分類失敗丟掉回報。 */
    private static OutcomeReason parseOutcomeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        try {
            return OutcomeReason.valueOf(reason.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static TaskPriority parsePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return TaskPriority.NORMAL;
        }
        try {
            return TaskPriority.valueOf(priority.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TaskPriority.NORMAL;
        }
    }

    private static Task.Category parseCategory(String value) {
        try {
            return Task.Category.valueOf(value == null ? "OTHER" : value.toUpperCase());
        } catch (Exception e) {
            return Task.Category.OTHER;
        }
    }

    private static Task.Recurrence parseRecurrence(String value) {
        try {
            return Task.Recurrence.valueOf(value == null ? "NONE" : value.toUpperCase());
        } catch (Exception e) {
            return Task.Recurrence.NONE;
        }
    }

    private static ScheduleItem.Recurrence parseScheduleRecurrence(IntentCommand command) {
        String value = command.safeOptions().recurrence();
        if (value != null && !value.isBlank()) {
            try {
                return ScheduleItem.Recurrence.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 未知週期退回既有 recurring 布林語意，避免 LLM 拼字錯誤造成資料遺失。
            }
        }
        return Boolean.TRUE.equals(command.recurring())
                ? ScheduleItem.Recurrence.WEEKLY : ScheduleItem.Recurrence.NONE;
    }

    private static java.time.LocalDate parseRecurrenceUntil(IntentCommand command) {
        String value = command.safeOptions().recurrenceUntil();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("bad recurrenceUntil: " + value, e);
        }
    }

    private static Task.ConditionType parseCondition(String value) {
        try {
            return Task.ConditionType.valueOf(value == null ? "NONE" : value.toUpperCase());
        } catch (Exception e) {
            return Task.ConditionType.NONE;
        }
    }

    private static com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType parseTrigger(String value) {
        try {
            return com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType.valueOf(
                    value == null ? "ENTER" : value.toUpperCase());
        } catch (Exception e) {
            return com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType.ENTER;
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private IntentResult handleFeedback(IntentCommand command) {
        String reason = command.reason() == null ? "" : command.reason().toUpperCase();
        if (reason.contains("DUPLICATE") || reason.contains("重複")) {
            var duplicates = taskService.listOpenTasks().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            task -> task.getTitle().strip().toLowerCase()))
                    .values().stream().filter(group -> group.size() > 1).toList();
            String detail = duplicates.isEmpty()
                    ? "我檢查了目前未完成待辦,沒有完全同名的重複項目。"
                    : "目前有重複候選:\n" + duplicates.stream()
                    .map(group -> group.stream().map(Task::getTitle).distinct()
                            .collect(java.util.stream.Collectors.joining("／"))
                            + " × " + group.size())
                    .collect(java.util.stream.Collectors.joining("\n"));
            return IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                    "你提醒得對，不應該重複建立。\n\n" + detail
                            + "\n\n要刪掉多出的項目時，告訴我名稱即可。");
        }
        // Only a stable machine-readable reason may resume the previous task. Free-form product
        // feedback often mentions「地點」while describing a proposed feature, and must not be
        // mistaken for an answer to an old task follow-up.
        if (reason.equals("MISSING_PLACE")) {
            Long id = conversationContextService.taskIdAt(null);
            if (id != null) {
                Task task = taskService.getTask(id);
                return IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                        "你說得對,我應該接著問。『%s』要在哪裡做?".formatted(task.getTitle()));
            }
        }
        return IntentResult.feedbackReceived();
    }
}
