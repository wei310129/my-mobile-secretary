package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleFollowUpService.OutcomeRecorded;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.OutcomeReason;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 意圖編排:解析 → 驗證 → 執行。
 *
 * 可靠度鐵律:LLM 任何失敗(沒設金鑰、逾時、輸出格式爛)都不能吞掉使用者的話——
 * 一律 fallback 把原文存成一般任務,寧可少聰明,不可丟資料。
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
    private final ConversationContextService conversationContextService;
    private final com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService placeAliasService;
    private final int bindRadiusMeters;
    private final Clock clock;

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
        this.conversationContextService = conversationContextService;
        this.placeAliasService = placeAliasService;
        this.bindRadiusMeters = bindRadiusMeters;
        this.clock = clock;
    }

    /** 處理使用者的一句話,回傳做了什麼;聽不懂/退回保底的話語會記成意圖問題供開發追蹤。 */
    public IntentResult handle(String text) {
        IntentResult result = doHandle(text);
        recordIssueIfUnresolved(text, result);
        conversationContextService.rememberExchange(text, result);
        return result;
    }

    private IntentResult doHandle(String text) {
        Optional<String> help = capabilityHelp(text);
        if (help.isPresent()) {
            return IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, help.get());
        }
        IntentScript script;
        IntentInterpreter interpreter = interpreterProvider.getIfAvailable();
        if (interpreter == null) {
            return fallbackTask(text, "意圖解析未啟用");
        }
        try {
            script = interpreter.interpret(text, Instant.now(clock), conversationContextService.snapshot());
        } catch (Exception e) {
            log.warn("Intent interpretation failed, falling back to plain task", e);
            return fallbackTask(text, "AI 暫時無法使用");
        }
        if (script == null || script.commands() == null || script.commands().isEmpty()) {
            return fallbackTask(text, "解析結果是空的");
        }

        // 單一操作:維持原語意(驗證失敗 → 整句保底)
        if (script.commands().size() == 1) {
            try {
                return execute(text, script.commands().get(0));
            } catch (IllegalArgumentException e) {
                // LLM 輸出未通過驗證(時間格式爛、缺欄位)→ 同樣不丟資料
                log.warn("Intent command invalid ({}), falling back to plain task", e.getMessage());
                return fallbackTask(text, "解析結果不完整");
            } catch (com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException e) {
                // 業務錯誤(如 Google 查不到地點)→ 轉成可讀回覆;
                // 絕不能往 webhook 洩漏成非 200,否則 LINE 會重送整包事件
                log.warn("Intent command hit business rule: {}", e.getMessage());
                return IntentResult.clarificationNeeded(e.getMessage());
            }
        }

        // 多操作(「取消A,B也取消,C改到11點」):逐一執行,單項失敗不拖垮其他項
        java.util.List<String> lines = new java.util.ArrayList<>();
        int failed = 0;
        for (IntentCommand command : script.commands()) {
            try {
                lines.add(execute(text, command).message());
            } catch (Exception e) {
                log.warn("Batch intent command failed", e);
                failed++;
                lines.add("有一項我處理不了,請單獨再講一次。");
            }
        }
        if (failed == script.commands().size()) {
            return fallbackTask(text, "多項操作都解析失敗");
        }
        return IntentResult.batchExecuted(lines);
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

    /** 依驗證後的 command 執行;LLM 輸出一律先驗證再信。 */
    private IntentResult execute(String text, IntentCommand command) {
        if (command == null || command.type() == null) {
            throw new IllegalArgumentException("missing type");
        }
        return switch (command.type()) {
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
                if (command.placeName() != null && !command.placeName().isBlank()) {
                    Place place = resolvePlace(command.placeName())
                            .orElseGet(() -> placeService.createPlace(command.placeName(), null, null, null, null));
                    var trigger = parseTrigger(options.triggerType());
                    if (!geofenceRuleService.ruleExists(task.getId(), place.getId(), trigger)) {
                        geofenceRuleService.createRule(task.getId(), place.getId(),
                                positive(options.radiusMeters(), bindRadiusMeters), trigger);
                    }
                }
                yield IntentResult.taskCreated(task, options.clarificationQuestion());
            }
            case CREATE_SCHEDULE -> {
                requireText(command.title(), "title");
                Instant startAt = parseTime(command.startAt());
                Instant endAt = parseTime(command.endAt());
                if (startAt == null || endAt == null) {
                    throw new IllegalArgumentException("schedule missing startAt/endAt");
                }
                Long placeId = resolvePlace(command.placeName()).map(Place::getId).orElse(null);
                ScheduleDecision decision = scheduleService.createSchedule(
                        command.title(), startAt, endAt, placeId,
                        Boolean.TRUE.equals(command.recurring()));
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
                                scheduleService.setWeeklyRecurrence(match.item().getId(), recurring));
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
            case ASK_PLACE -> {
                requireText(command.placeName(), "placeName");
                yield resolvePlace(command.placeName())
                        .map(IntentResult::placeInfo)
                        .orElseGet(() -> IntentResult.clarificationNeeded(
                                "我沒有叫「%s」的地點紀錄,說「建立地點:%s」我就去 Google 查來存。"
                                        .formatted(command.placeName(), command.placeName())));
            }
            case CREATE_PLACE -> {
                requireText(command.placeName(), "placeName");
                // 已有同名(含)地點就不重建,直接回資訊
                var existing = resolvePlace(command.placeName());
                if (existing.isPresent()) {
                    yield IntentResult.placeInfo(existing.get());
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
            case UNKNOWN -> IntentResult.clarificationNeeded(
                    command.reason() == null || command.reason().isBlank()
                            ? "我沒聽懂,可以換個說法嗎?" : command.reason());
        };
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

        var missing = tasks.stream().filter(t -> t.getDueAt() == null).limit(3).toList();
        if (!missing.isEmpty()) {
            // 段落間空一行(使用者 2026-07-15 的格式範例)
            advice.append("\n\n%s還沒有時間或地點,補一句(例:「%s這週六早上處理」)我才能主動提醒。"
                    .formatted(
                            missing.stream().map(t -> "「" + t.getTitle() + "」")
                                    .collect(java.util.stream.Collectors.joining("、")),
                            missing.get(0).getTitle()));
        }

        Instant now = Instant.now(clock);
        tasks.stream()
                .filter(t -> t.getDueAt() != null && t.getDueAt().isAfter(now))
                .findFirst()
                .flatMap(t -> suggestSlotBefore(t, now))
                .ifPresent(advice::append);
        return advice.toString();
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
                    .collect(java.util.stream.Collectors.joining("、"));
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "有 %d 個行程都符合:%s,告訴我日期或時間我才不會%s錯。"
                            .formatted(matches.size(), titles, actionVerb)));
        }
        return new ScheduleMatch(matches.get(0), null);
    }

    /** 行程關鍵字配對結果:item 與 failure 恰有一個非空。 */
    private record ScheduleMatch(ScheduleItem item, IntentResult failure) {
    }

    /** LLM 失敗時的保底:原文直接存成任務(仍會觸發品項自動綁定)。 */
    private IntentResult fallbackTask(String text, String why) {
        Task task = taskService.createTask(text, null, TaskPriority.NORMAL, null);
        return IntentResult.fallbackTaskCreated(task, why);
    }

    /** 地點名稱解析:先精確比對,再包含比對(規則式;不讓 LLM 決定 id)。 */
    private Optional<Place> resolvePlace(String placeName) {
        return placeAliasService.resolve(placeName);
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
                    : "目前有重複候選:" + duplicates.stream()
                    .map(group -> group.stream().map(Task::getTitle).distinct()
                            .collect(java.util.stream.Collectors.joining("／"))
                            + " × " + group.size())
                    .collect(java.util.stream.Collectors.joining("、"));
            return IntentResult.message(IntentResult.Action.FEEDBACK_RECEIVED,
                    "你提醒得對,不應該重複建立。" + detail + "要刪掉多出的項目時告訴我名稱即可。");
        }
        if (reason.contains("MISSING_PLACE") || reason.contains("地點")) {
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
