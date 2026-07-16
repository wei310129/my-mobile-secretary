package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ItemService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PlanningPreferenceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import com.aproject.aidriven.mymobilesecretary.planner.application.RouteSuggestionService;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelPlanningService;
import com.aproject.aidriven.mymobilesecretary.planner.application.WeatherAdvisoryService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskInsightService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderPreferenceService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderPreference;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleInsightService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 100 條生活語句中較高階、跨模組的執行層。
 * 語句如何分類仍由 LLM 負責;本類只接受 typed command 並做確定性執行。
 */
@Service
public class LifestyleIntentService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final ItemService itemService;
    private final PriceRecordService priceService;
    private final PlaceAliasService placeAliasService;
    private final PlaceService placeService;
    private final GeofenceRuleService geofenceService;
    private final FreeSlotService freeSlotService;
    private final RouteSuggestionService routeSuggestionService;
    private final TravelPlanningService travelPlanningService;
    private final WeatherAdvisoryService weatherService;
    private final PlanningPreferenceService preferenceService;
    private final ConversationContextService contextService;
    private final ReminderPreferenceService reminderPreferenceService;
    private final ScheduleInsightService scheduleInsightService;
    private final TaskInsightService taskInsightService;
    private final Clock clock;

    public LifestyleIntentService(TaskService taskService, ScheduleService scheduleService,
                                  ItemService itemService, PriceRecordService priceService,
                                  PlaceAliasService placeAliasService, PlaceService placeService,
                                  GeofenceRuleService geofenceService, FreeSlotService freeSlotService,
                                  RouteSuggestionService routeSuggestionService,
                                  TravelPlanningService travelPlanningService,
                                  WeatherAdvisoryService weatherService,
                                  PlanningPreferenceService preferenceService,
                                  ConversationContextService contextService,
                                  ReminderPreferenceService reminderPreferenceService,
                                  ScheduleInsightService scheduleInsightService,
                                  TaskInsightService taskInsightService,
                                  Clock clock) {
        this.taskService = taskService;
        this.scheduleService = scheduleService;
        this.itemService = itemService;
        this.priceService = priceService;
        this.placeAliasService = placeAliasService;
        this.placeService = placeService;
        this.geofenceService = geofenceService;
        this.freeSlotService = freeSlotService;
        this.routeSuggestionService = routeSuggestionService;
        this.travelPlanningService = travelPlanningService;
        this.weatherService = weatherService;
        this.preferenceService = preferenceService;
        this.contextService = contextService;
        this.reminderPreferenceService = reminderPreferenceService;
        this.scheduleInsightService = scheduleInsightService;
        this.taskInsightService = taskInsightService;
        this.clock = clock;
    }

    public IntentResult execute(String text, IntentCommand command) {
        try {
            return executeValidated(command);
        } catch (IllegalArgumentException e) {
            return IntentResult.clarificationNeeded(clarificationFor(e.getMessage()));
        }
    }

    private IntentResult executeValidated(IntentCommand command) {
        IntentOptions o = command.safeOptions();
        return switch (command.type()) {
            case ADD_SCHEDULE_REMINDER -> addScheduleReminder(command, o);
            case SUGGEST_FREE_SLOT -> suggestFreeSlot(command, o);
            case CREATE_RELATIVE_SCHEDULE -> createRelativeSchedule(command, o);
            case LIST_AGENDA -> listAgenda(command, o);
            case ASK_TASK_INFO -> askTaskInfo(command, o);
            case ASK_AVAILABILITY -> askAvailability(command);
            case LIST_RECENT -> listRecent();
            case SUGGEST_ROUTE_TASKS -> suggestRoute(command);
            case SET_PLACE_ALIAS -> setPlaceAlias(command, o);
            case ADD_SHOPPING_ITEMS -> addShopping(command, o);
            case REMOVE_SHOPPING_ITEM -> removeShopping(command, o);
            case LIST_SHOPPING_ITEMS -> listShopping();
            case SET_INVENTORY -> setInventory(command, o);
            case ASK_PRICE_COMPARISON -> comparePrices(command);
            case ASK_WEATHER -> IntentResult.message(IntentResult.Action.WEATHER_INFO,
                    weatherService.describeCurrentForecast().orElse("目前拿不到天氣預報,稍後再試。"));
            case CREATE_WEATHER_REMINDER -> createWeatherReminder(command, o);
            case ASK_TRAVEL_TIME -> askTravelTime(command, o);
            case ASK_DEPARTURE_TIME -> askDepartureTime(command, o);
            case CREATE_TRAFFIC_WATCH -> createTrafficWatch(command, o);
            case CHECK_FEASIBILITY -> checkConnection(command, o);
            case SET_PLANNING_BUFFER -> setPlanningBuffer(o);
            case ACCEPT_CONTEXT -> acceptContext();
            case SHIFT_CONTEXT_LATER -> shiftContext(command, o);
            case CANCEL_CONTEXT -> cancelContext(o);
            case SET_CONTEXT_PLACE -> setContextPlace(command, o);
            case COPY_CONTEXT -> copyContext(command, o);
            case SOCIAL -> IntentResult.message(IntentResult.Action.SOCIAL_REPLIED,
                    command.reason() == null || command.reason().isBlank() ? "不客氣,有需要再叫我。" : command.reason());
            case UPDATE_TASK -> updateTask(command, o);
            case PAUSE_RECURRING_TASK -> pauseRecurring(command, o);
            case RESUME_RECURRING_TASK -> resumeRecurring(command, o);
            case SKIP_RECURRING_OCCURRENCE -> skipRecurring(command, o);
            case LIST_COMPLETED_TASKS -> listCompleted(o);
            case MARK_SHOPPING_PURCHASED -> markShoppingPurchased(command, o);
            case CLEAR_SHOPPING_LIST -> clearShopping();
            case LIST_SHOPPING_BY_PLACE -> listShoppingAt(command);
            case AGENDA_SUMMARY -> agendaSummary(o);
            case RESIZE_SCHEDULE -> resizeSchedule(command, o);
            case ADJUST_INVENTORY -> adjustInventory(command, o);
            case LIST_INVENTORY -> listInventory(o);
            case ASK_ITEM_PLACES -> askItemPlaces(command);
            case BIND_ITEM_PLACE -> bindItemPlace(command);
            case LIST_ITEMS_BY_PLACE -> listItemsAt(command);
            case GROUP_SHOPPING_BY_PLACE -> groupShoppingByPlace();
            case RESTOCK_LOW_INVENTORY -> restockLowInventory(o);
            case SET_QUIET_HOURS -> setQuietHours(o);
            case CLEAR_QUIET_HOURS -> clearQuietHours();
            case MUTE_REMINDERS -> muteReminders(command);
            case RESUME_REMINDERS -> resumeReminders();
            case ASK_REMINDER_PREFERENCES -> reminderPreferences();
            case LIST_LOCATION_TASKS -> listLocationTasks(o);
            case ASK_PLACE_TASKS -> askPlaceTasks(command, o);
            case ASK_TASK_GEOFENCE -> askTaskGeofence(command, o);
            case UPDATE_TASK_GEOFENCE -> updateTaskGeofence(command, o);
            case REMOVE_TASK_PLACE -> removeTaskPlace(command, o);
            case ASK_NEXT_SCHEDULE -> nextSchedule();
            case ASK_SCHEDULE_GAP -> scheduleGap(command, o);
            case GROUP_SCHEDULES_BY_DAY -> groupSchedules(o);
            case CHECK_SCHEDULE_CONFLICTS -> checkScheduleConflicts(o);
            case SUGGEST_NEXT_TASK -> suggestNextTask(o);
            case GROUP_TASKS_BY_CATEGORY -> groupTasksByCategory();
            case ASK_TASK_PROGRESS -> taskProgress(o);
            case GROUP_TASKS_BY_DUE -> groupTasksByDue();
            case ASK_TASK_LOAD -> taskLoad(o);
            case ASK_BUSY_TASK_DAY -> busiestTaskDay();
            case ASK_BUSY_SCHEDULE_DAY -> busiestScheduleDay(o);
            case ASK_LONGEST_SCHEDULE -> longestSchedule(o);
            case GROUP_SCHEDULES_BY_PLACE -> groupSchedulesByPlace(o);
            default -> throw new IllegalArgumentException("not a lifestyle command: " + command.type());
        };
    }

    public IntentResult listTasks(IntentCommand command, String advice) {
        List<Task> tasks = filterTasks(taskService.listOpenTasks(), command.safeOptions());
        contextService.rememberTaskList(tasks);
        return IntentResult.tasksListed(tasks, advice);
    }

    public IntentResult listSchedules(IntentCommand command) {
        List<ScheduleItem> items = filterSchedules(upcomingSchedules(), command.safeOptions());
        contextService.rememberScheduleList(items);
        return IntentResult.schedulesListed(items);
    }

    private IntentResult addScheduleReminder(IntentCommand command, IntentOptions o) {
        ScheduleItem item = scheduleTarget(command, o);
        int lead = positive(o.leadMinutes(), 10);
        Instant due = item.getStartAt().minus(Duration.ofMinutes(lead));
        Task task = taskService.createTask("提醒:" + item.getTitle(),
                "行程開始前 %d 分鐘".formatted(lead), TaskPriority.NORMAL, due,
                Task.Category.OTHER, Task.Recurrence.NONE, Task.ConditionType.NONE, null);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.SCHEDULE_REMINDER_CREATED,
                "已設定「%s」開始前 %d 分鐘提醒(%s)。".formatted(
                        item.getTitle(), lead, format(due)));
    }

    private IntentResult suggestFreeSlot(IntentCommand command, IntentOptions o) {
        Instant from = parse(command.startAt());
        Instant until = parse(command.endAt());
        if ("FREEST_DAY".equalsIgnoreCase(o.filter())) {
            FreeSlotService.DayLoad day = freeSlotService.freestDay(from, until);
            return IntentResult.message(IntentResult.Action.FREE_SLOTS_SUGGESTED,
                    "%s最空,目前已排約 %d 分鐘。".formatted(day.date(), day.busy().toMinutes()));
        }
        List<FreeSlotService.Slot> slots = freeSlotService.suggest(from, until,
                Duration.ofMinutes(positive(o.durationMinutes(), 60)), o.timeOfDay());
        if (slots.isEmpty()) {
            return IntentResult.message(IntentResult.Action.FREE_SLOTS_SUGGESTED,
                    "指定範圍內找不到足夠長的空檔。可以縮短時長或換一天。 ");
        }
        String lines = java.util.stream.IntStream.range(0, slots.size())
                .mapToObj(i -> "%d. %s–%s".formatted(i + 1, format(slots.get(i).startAt()),
                        ZonedDateTime.ofInstant(slots.get(i).endAt(), TAIPEI)
                                .format(DateTimeFormatter.ofPattern("HH:mm"))))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.FREE_SLOTS_SUGGESTED,
                "可以考慮這些空檔:\n" + lines);
    }

    private IntentResult createRelativeSchedule(IntentCommand command, IntentOptions o) {
        require(command.title(), "title");
        require(o.referenceTitle(), "referenceTitle");
        ScheduleItem reference = uniqueSchedule(o.referenceTitle());
        Duration duration = Duration.ofMinutes(positive(o.durationMinutes(), 60));
        boolean after = "AFTER".equalsIgnoreCase(o.referenceKind());
        Instant start = after ? reference.getEndAt() : reference.getStartAt().minus(duration);
        Instant end = after ? start.plus(duration) : reference.getStartAt();
        Long placeId = resolvePlace(command.placeName()).map(Place::getId).orElse(null);
        var decision = scheduleService.createSchedule(command.title(), start, end, placeId, false);
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleDecided(decision);
    }

    private IntentResult listAgenda(IntentCommand command, IntentOptions o) {
        List<Task> tasks = filterTasks(taskService.listOpenTasks(), o);
        List<ScheduleItem> schedules = filterSchedules(upcomingSchedules(), o);
        contextService.rememberTaskList(tasks);
        contextService.rememberScheduleList(schedules);
        if (tasks.isEmpty() && schedules.isEmpty()) {
            return IntentResult.message(IntentResult.Action.AGENDA_LISTED, "指定範圍內沒有待辦或行程。 ");
        }
        String taskLines = tasks.stream().limit(10)
                .map(t -> "待辦｜%s%s".formatted(t.getTitle(),
                        t.getDueAt() == null ? "" : "｜" + format(t.getDueAt())))
                .collect(java.util.stream.Collectors.joining("\n"));
        String scheduleLines = schedules.stream().limit(10)
                .map(s -> "行程｜%s｜%s".formatted(s.getTitle(), format(s.getStartAt())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.AGENDA_LISTED,
                java.util.stream.Stream.of(scheduleLines, taskLines).filter(v -> !v.isBlank())
                        .collect(java.util.stream.Collectors.joining("\n")));
    }

    private IntentResult askTaskInfo(IntentCommand command, IntentOptions o) {
        Task task = taskTarget(command, o);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.TASK_INFO,
                "「%s」%s,分類 %s,狀態 %s。".formatted(task.getTitle(),
                        task.getDueAt() == null ? "沒有期限" : "期限 " + format(task.getDueAt()),
                        task.getCategory(), task.getStatus()));
    }

    private IntentResult askAvailability(IntentCommand command) {
        Instant from = requiredTime(command.startAt(), "startAt");
        Instant until = requiredTime(command.endAt(), "endAt");
        boolean available = freeSlotService.available(from, until);
        return IntentResult.message(IntentResult.Action.AVAILABILITY_CHECKED,
                available ? "%s 到 %s 有空。".formatted(format(from), format(until))
                        : "%s 到 %s 已有行程。".formatted(format(from), format(until)));
    }

    private IntentResult listRecent() {
        record Recent(String label, Instant at) {}
        List<Recent> recent = java.util.stream.Stream.concat(
                        taskService.listTasks().stream().map(t -> new Recent("待辦「%s」".formatted(t.getTitle()), t.getCreatedAt())),
                        scheduleService.listSchedules(null).stream().map(s -> new Recent("行程「%s」".formatted(s.getTitle()), s.getCreatedAt())))
                .sorted(Comparator.comparing(Recent::at).reversed()).limit(5).toList();
        String message = recent.isEmpty() ? "最近沒有新增內容。" : recent.stream()
                .map(r -> "%s｜%s".formatted(r.label(), format(r.at())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.RECENT_ACTIVITY_LISTED, message);
    }

    private IntentResult suggestRoute(IntentCommand command) {
        Place destination = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        return IntentResult.message(IntentResult.Action.ROUTE_SUGGESTED,
                routeSuggestionService.suggest(destination));
    }

    private IntentResult setPlaceAlias(IntentCommand command, IntentOptions o) {
        require(o.alias(), "alias");
        Place place = resolvePlace(command.placeName()).orElseGet(() ->
                placeService.createPlace(command.placeName(), null, null, null, null));
        placeAliasService.remember(o.alias(), place.getId());
        contextService.rememberPlace(place.getId());
        return IntentResult.message(IntentResult.Action.PLACE_ALIAS_SET,
                "記住了,「%s」就是「%s」。".formatted(o.alias(), place.getName()));
    }

    private IntentResult addShopping(IntentCommand command, IntentOptions o) {
        List<String> names = itemNames(command, o);
        List<Item> items = itemService.addShoppingItems(names);
        if (command.placeName() != null && !command.placeName().isBlank()) {
            Place place = resolvePlace(command.placeName()).orElseGet(() ->
                    placeService.createPlace(command.placeName(), null, null, null, null));
            items.forEach(item -> itemService.bindItemToPlace(item.getName(), place.getId()));
        }
        return IntentResult.message(IntentResult.Action.SHOPPING_ITEMS_ADDED,
                "已加入購物清單:%s。重複品項不會再新增一份。".formatted(
                        items.stream().map(Item::getName).collect(java.util.stream.Collectors.joining("、"))));
    }

    private IntentResult removeShopping(IntentCommand command, IntentOptions o) {
        String name = itemNames(command, o).getFirst();
        boolean removed = itemService.removeShoppingItem(name).isPresent();
        return IntentResult.message(IntentResult.Action.SHOPPING_ITEM_REMOVED,
                removed ? "已從購物清單移除「%s」。".formatted(name)
                        : "購物清單裡沒有「%s」。".formatted(name));
    }

    private IntentResult listShopping() {
        List<Item> items = itemService.listShoppingItems();
        String message = items.isEmpty() ? "購物清單目前是空的。" : "還要買:" +
                items.stream().map(Item::getName).collect(java.util.stream.Collectors.joining("、"));
        return IntentResult.message(IntentResult.Action.SHOPPING_LISTED, message);
    }

    private IntentResult setInventory(IntentCommand command, IntentOptions o) {
        String name = itemNames(command, o).getFirst();
        Item item = itemService.setInventory(name, o.quantity() == null ? 0 : o.quantity());
        return IntentResult.message(IntentResult.Action.INVENTORY_UPDATED,
                "已更新「%s」庫存為 %d。".formatted(item.getName(), item.getInventoryQuantity()));
    }

    private IntentResult updateTask(IntentCommand command, IntentOptions o) {
        Task target = taskTarget(command, o);
        TaskPriority priority = null;
        if (command.priority() != null && !command.priority().isBlank()) {
            try { priority = TaskPriority.valueOf(command.priority().toUpperCase()); }
            catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("bad priority"); }
        }
        Task.Category category = null;
        if (o.category() != null) {
            try { category = Task.Category.valueOf(o.category().toUpperCase()); }
            catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("bad category"); }
        }
        if (o.newTitle() == null && o.description() == null && priority == null && category == null) {
            throw new IllegalArgumentException("missing task changes");
        }
        Task changed = taskService.updateTask(target.getId(), o.newTitle(), o.description(), priority, category);
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.TASK_UPDATED,
                "已更新待辦「%s」:分類 %s、優先級 %s。".formatted(
                        changed.getTitle(), changed.getCategory(), changed.getPriority()), changed);
    }

    private IntentResult pauseRecurring(IntentCommand command, IntentOptions o) {
        Task target = taskTarget(command, o);
        Task changed = taskService.pauseRecurrence(target.getId());
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.RECURRENCE_PAUSED,
                "已暫停「%s」的固定提醒;原設定保留,之後可以直接恢復。".formatted(changed.getTitle()), changed);
    }

    private IntentResult resumeRecurring(IntentCommand command, IntentOptions o) {
        Task target = taskTarget(command, o);
        Task changed = taskService.resumeRecurrence(target.getId());
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.RECURRENCE_RESUMED,
                "已恢復「%s」的固定提醒,下一次是 %s。".formatted(
                        changed.getTitle(), format(changed.getDueAt())), changed);
    }

    private IntentResult skipRecurring(IntentCommand command, IntentOptions o) {
        Task target = taskTarget(command, o);
        Task changed = taskService.skipRecurringOccurrence(target.getId());
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.RECURRENCE_SKIPPED,
                "已略過「%s」這一次,下一次是 %s。".formatted(
                        changed.getTitle(), format(changed.getDueAt())), changed);
    }

    private IntentResult listCompleted(IntentOptions o) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TAIPEI);
        String filter = o.filter() == null ? "RECENT" : o.filter().toUpperCase();
        List<Task> completed = taskService.listCompletedTasks().stream().filter(task -> {
            LocalDate date = LocalDate.ofInstant(task.getUpdatedAt(), TAIPEI);
            return switch (filter) {
                case "TODAY" -> date.equals(today);
                case "WEEK" -> !date.isBefore(today.minusDays(6));
                default -> true;
            };
        }).limit(20).toList();
        contextService.rememberTaskList(completed);
        String message = completed.isEmpty() ? "指定範圍內還沒有完成紀錄。" :
                "已完成 %d 件:\n%s".formatted(completed.size(), completed.stream()
                        .map(task -> "✓ %s｜%s".formatted(task.getTitle(), format(task.getUpdatedAt())))
                        .collect(java.util.stream.Collectors.joining("\n")));
        return IntentResult.message(IntentResult.Action.COMPLETED_TASKS_LISTED, message);
    }

    private IntentResult markShoppingPurchased(IntentCommand command, IntentOptions o) {
        List<String> names = itemNames(command, o);
        List<Item> purchased = itemService.markShoppingPurchased(names, o.quantity());
        if (purchased.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SHOPPING_ITEMS_PURCHASED,
                    "這些品項目前不在購物清單裡。 ");
        }
        return IntentResult.message(IntentResult.Action.SHOPPING_ITEMS_PURCHASED,
                "已標記買到:%s。".formatted(purchased.stream().map(Item::getName)
                        .collect(java.util.stream.Collectors.joining("、"))));
    }

    private IntentResult clearShopping() {
        List<Item> cleared = itemService.clearShoppingList();
        return IntentResult.message(IntentResult.Action.SHOPPING_LIST_CLEARED,
                cleared.isEmpty() ? "購物清單本來就是空的。" : "已清空購物清單,共 %d 項。".formatted(cleared.size()));
    }

    private IntentResult adjustInventory(IntentCommand command, IntentOptions o) {
        require(command.title(), "title");
        if (o.quantity() == null || o.quantity() == 0) {
            throw new IllegalArgumentException("missing inventory delta");
        }
        Item item = itemService.adjustInventory(command.title(), o.quantity());
        return IntentResult.message(IntentResult.Action.INVENTORY_ADJUSTED,
                "已把「%s」庫存%s %d,目前是 %d。".formatted(item.getName(),
                        o.quantity() > 0 ? "增加" : "減少", Math.abs(o.quantity()),
                        item.getInventoryQuantity()));
    }

    private IntentResult listInventory(IntentOptions o) {
        Integer maximum = "LOW".equalsIgnoreCase(o.filter())
                ? (o.quantity() == null ? 1 : o.quantity()) : null;
        List<Item> items = itemService.listInventory(maximum);
        String message = items.isEmpty() ? (maximum == null ? "目前沒有大於 0 的庫存紀錄。" : "沒有已知的低庫存品項。")
                : items.stream().map(item -> "%s｜%d".formatted(item.getName(), item.getInventoryQuantity()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.INVENTORY_LISTED, message);
    }

    private IntentResult askItemPlaces(IntentCommand command) {
        require(command.title(), "title");
        Optional<Item> found = itemService.findItem(command.title());
        if (found.isEmpty()) {
            return IntentResult.clarificationNeeded("我還沒有「%s」的品項紀錄。".formatted(command.title()));
        }
        Item item = found.get();
        List<String> places = item.getPlaceIds().stream().map(placeService::getPlace)
                .map(Place::getName).sorted().toList();
        return IntentResult.message(IntentResult.Action.ITEM_PLACES_INFO,
                places.isEmpty() ? "還不知道「%s」可以在哪裡買。".formatted(item.getName())
                        : "「%s」可以在:%s。".formatted(item.getName(), String.join("、", places)));
    }

    private IntentResult bindItemPlace(IntentCommand command) {
        require(command.title(), "title");
        require(command.placeName(), "placeName");
        Place place = resolvePlace(command.placeName()).orElseGet(() ->
                placeService.createPlace(command.placeName(), null, null, null, null));
        Item item = itemService.bindItemToPlace(command.title(), place.getId());
        return IntentResult.message(IntentResult.Action.ITEM_PLACE_BOUND,
                "記住了,「%s」可以在「%s」買。".formatted(item.getName(), place.getName()));
    }

    private IntentResult listItemsAt(IntentCommand command) {
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        List<Item> items = itemService.listKnownItemsAt(place.getId());
        String message = items.isEmpty() ? "目前沒有記錄「%s」可買的品項。".formatted(place.getName())
                : "記得「%s」可買:%s。".formatted(place.getName(), items.stream().map(Item::getName)
                .collect(java.util.stream.Collectors.joining("、")));
        return IntentResult.message(IntentResult.Action.ITEMS_BY_PLACE_LISTED, message);
    }

    private IntentResult groupShoppingByPlace() {
        List<Item> shopping = itemService.listShoppingItems();
        if (shopping.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SHOPPING_GROUPED_BY_PLACE, "購物清單目前是空的。");
        }
        java.util.Map<Long, String> placeNames = placeService.listPlaces().stream()
                .collect(java.util.stream.Collectors.toMap(Place::getId, Place::getName));
        java.util.Map<String, java.util.List<String>> groups = new java.util.LinkedHashMap<>();
        for (Item item : shopping) {
            if (item.getPlaceIds().isEmpty()) {
                groups.computeIfAbsent("未指定店家", ignored -> new java.util.ArrayList<>()).add(item.getName());
            } else {
                for (Long placeId : item.getPlaceIds()) {
                    String place = placeNames.getOrDefault(placeId, "未知地點");
                    groups.computeIfAbsent(place, ignored -> new java.util.ArrayList<>()).add(item.getName());
                }
            }
        }
        String message = groups.entrySet().stream().map(entry -> "%s｜%s".formatted(
                        entry.getKey(), String.join("、", entry.getValue())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.SHOPPING_GROUPED_BY_PLACE, message);
    }

    private IntentResult restockLowInventory(IntentOptions o) {
        int maximum = o.quantity() == null ? 1 : Math.max(o.quantity(), 1);
        List<Item> items = itemService.restockLowInventory(maximum);
        return IntentResult.message(IntentResult.Action.LOW_INVENTORY_RESTOCKED,
                items.isEmpty() ? "沒有已盤點且低於門檻的庫存。"
                        : "已把低庫存加入購物清單:%s。".formatted(items.stream().map(Item::getName)
                        .collect(java.util.stream.Collectors.joining("、"))));
    }

    private IntentResult setQuietHours(IntentOptions o) {
        require(o.quietStart(), "quietStart");
        require(o.quietEnd(), "quietEnd");
        java.time.LocalTime start;
        java.time.LocalTime end;
        try {
            start = java.time.LocalTime.parse(o.quietStart());
            end = java.time.LocalTime.parse(o.quietEnd());
        } catch (Exception e) {
            throw new IllegalArgumentException("bad quiet hours");
        }
        boolean allowHigh = o.allowHighPriority() == null || o.allowHighPriority();
        reminderPreferenceService.setQuietHours(start, end, allowHigh);
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "已設定每日 %s–%s 勿擾;%s。".formatted(start, end,
                        allowHigh ? "緊急待辦仍會提醒" : "緊急待辦也會延後"));
    }

    private IntentResult clearQuietHours() {
        reminderPreferenceService.clearQuietHours();
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "已取消固定勿擾時段。 ");
    }

    private IntentResult muteReminders(IntentCommand command) {
        Instant until = requiredTime(command.dueAt(), "dueAt");
        if (!until.isAfter(Instant.now(clock))) {
            throw new IllegalArgumentException("mute time must be future");
        }
        ReminderPreference preference = reminderPreferenceService.muteUntil(until);
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "一般提醒已暫停到 %s;到時會自動恢復%s。".formatted(format(until),
                        preference.isAllowHighPriority() ? "，緊急待辦仍會提醒" : ""));
    }

    private IntentResult resumeReminders() {
        reminderPreferenceService.resumeNow();
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_UPDATED,
                "已取消臨時靜音;固定勿擾時段仍照原設定。 ");
    }

    private IntentResult reminderPreferences() {
        Optional<ReminderPreference> found = reminderPreferenceService.preference();
        if (found.isEmpty()) {
            return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_INFO,
                    "目前沒有固定勿擾或臨時靜音設定。 ");
        }
        ReminderPreference preference = found.get();
        String quiet = preference.getQuietStart() == null ? "無固定勿擾"
                : "每日 %s–%s 勿擾(%s緊急提醒)".formatted(
                preference.getQuietStart(), preference.getQuietEnd(),
                preference.isAllowHighPriority() ? "保留" : "包含");
        String mute = preference.getMutedUntil() != null
                && preference.getMutedUntil().isAfter(Instant.now(clock))
                ? "，臨時靜音到 " + format(preference.getMutedUntil()) : "";
        return IntentResult.message(IntentResult.Action.REMINDER_PREFERENCE_INFO, quiet + mute + "。");
    }

    private IntentResult listLocationTasks(IntentOptions o) {
        java.util.Set<Long> boundIds = geofenceService.listAllRules().stream()
                .map(rule -> rule.getTaskId()).collect(java.util.stream.Collectors.toSet());
        boolean unbound = "UNBOUND".equalsIgnoreCase(o.filter());
        List<Task> tasks = taskService.listOpenTasks().stream()
                .filter(task -> unbound != boundIds.contains(task.getId()))
                .toList();
        contextService.rememberTaskList(tasks);
        String message = tasks.isEmpty() ? (unbound ? "所有未完成待辦都已有地點規則。" : "目前沒有地點提醒待辦。")
                : tasks.stream().map(task -> "「%s」".formatted(task.getTitle()))
                .collect(java.util.stream.Collectors.joining("、"));
        return IntentResult.message(IntentResult.Action.LOCATION_TASKS_LISTED, message);
    }

    private IntentResult askPlaceTasks(IntentCommand command, IntentOptions o) {
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        TriggerType trigger = o.triggerType() == null ? null : parseTrigger(o.triggerType());
        java.util.Map<Long, Task> open = taskService.listOpenTasks().stream()
                .collect(java.util.stream.Collectors.toMap(Task::getId, task -> task));
        List<String> lines = geofenceService.listRulesForPlace(place.getId()).stream()
                .filter(rule -> trigger == null || rule.getTriggerType() == trigger)
                .filter(rule -> open.containsKey(rule.getTaskId()))
                .map(rule -> "%s｜%s｜%dm".formatted(open.get(rule.getTaskId()).getTitle(),
                        rule.getTriggerType(), rule.getRadiusMeters()))
                .toList();
        return IntentResult.message(IntentResult.Action.PLACE_TASKS_INFO,
                lines.isEmpty() ? "目前到「%s」沒有會觸發的未完成待辦。".formatted(place.getName())
                        : "「%s」的提醒:\n%s".formatted(place.getName(), String.join("\n", lines)));
    }

    private IntentResult askTaskGeofence(IntentCommand command, IntentOptions o) {
        Task task = taskTarget(command, o);
        List<String> lines = geofenceService.listRulesForTask(task.getId()).stream()
                .map(rule -> "%s｜%s｜半徑 %dm".formatted(
                        placeService.getPlace(rule.getPlaceId()).getName(),
                        rule.getTriggerType(), rule.getRadiusMeters()))
                .toList();
        return IntentResult.message(IntentResult.Action.TASK_GEOFENCE_INFO,
                lines.isEmpty() ? "「%s」目前沒有地點提醒。".formatted(task.getTitle())
                        : "「%s」的地點提醒:\n%s".formatted(task.getTitle(), String.join("\n", lines)));
    }

    private IntentResult updateTaskGeofence(IntentCommand command, IntentOptions o) {
        Task task = taskTarget(command, o);
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        TriggerType trigger = o.triggerType() == null ? null : parseTrigger(o.triggerType());
        if (o.radiusMeters() == null && trigger == null) {
            throw new IllegalArgumentException("missing geofence changes");
        }
        var rule = geofenceService.updateUniqueRule(task.getId(), place.getId(), o.radiusMeters(), trigger);
        return IntentResult.message(IntentResult.Action.TASK_GEOFENCE_UPDATED,
                "已把「%s」在「%s」的提醒改為 %s、半徑 %d 公尺。".formatted(
                        task.getTitle(), place.getName(), rule.getTriggerType(), rule.getRadiusMeters()));
    }

    private IntentResult removeTaskPlace(IntentCommand command, IntentOptions o) {
        Task task = taskTarget(command, o);
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        geofenceService.removeUniqueRule(task.getId(), place.getId());
        return IntentResult.message(IntentResult.Action.TASK_PLACE_REMOVED,
                "已移除「%s」在「%s」的地點提醒;待辦本身仍保留。".formatted(
                        task.getTitle(), place.getName()));
    }

    private IntentResult nextSchedule() {
        Optional<ScheduleItem> found = scheduleInsightService.next();
        if (found.isEmpty()) {
            return IntentResult.message(IntentResult.Action.NEXT_SCHEDULE_INFO, "接下來沒有已確認行程。 ");
        }
        ScheduleItem item = found.get();
        contextService.rememberSchedule(item);
        Duration until = Duration.between(Instant.now(clock), item.getStartAt());
        String timing = until.isNegative() || until.isZero()
                ? "正在進行中" : "還有 %d 小時 %d 分鐘".formatted(until.toHours(), until.toMinutesPart());
        String place = item.getPlaceId() == null ? "" : "｜" + placeService.getPlace(item.getPlaceId()).getName();
        return IntentResult.message(IntentResult.Action.NEXT_SCHEDULE_INFO,
                "下一個是「%s」｜%s%s，%s。".formatted(item.getTitle(), format(item.getStartAt()), place, timing));
    }

    private IntentResult scheduleGap(IntentCommand command, IntentOptions o) {
        ScheduleItem first;
        ScheduleItem second;
        if (command.title() != null && o.referenceTitle() != null) {
            first = uniqueSchedule(command.title());
            second = uniqueSchedule(o.referenceTitle());
        } else {
            List<ScheduleItem> upcoming = scheduleInsightService.upcoming();
            if (upcoming.size() < 2) {
                return IntentResult.clarificationNeeded("至少要有兩個接下來的行程才能算間隔。 ");
            }
            first = upcoming.get(0);
            second = upcoming.get(1);
        }
        var gap = scheduleInsightService.gap(first, second);
        String message = gap.overlapping()
                ? "「%s」和「%s」重疊 %d 分鐘。".formatted(gap.first().getTitle(), gap.second().getTitle(),
                Math.abs(gap.duration().toMinutes()))
                : "「%s」結束到「%s」開始有 %d 分鐘。".formatted(gap.first().getTitle(),
                gap.second().getTitle(), gap.duration().toMinutes());
        return IntentResult.message(IntentResult.Action.SCHEDULE_GAP_INFO, message);
    }

    private IntentResult groupSchedules(IntentOptions o) {
        List<ScheduleItem> items = filterSchedules(scheduleInsightService.upcoming(), o);
        var groups = scheduleInsightService.groupByDay(items);
        if (groups.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_DAY,
                    "指定範圍內沒有已確認行程。 ");
        }
        String message = groups.entrySet().stream().map(entry -> "%s\n%s".formatted(entry.getKey(),
                        entry.getValue().stream().map(item -> "- %s｜%s".formatted(item.getTitle(),
                                ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI)
                                        .format(DateTimeFormatter.ofPattern("HH:mm"))))
                                .collect(java.util.stream.Collectors.joining("\n"))))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_DAY, message);
    }

    private IntentResult checkScheduleConflicts(IntentOptions o) {
        List<ScheduleItem> items = filterSchedules(scheduleInsightService.upcoming(), o);
        var conflicts = scheduleInsightService.conflicts(items);
        String message = conflicts.isEmpty() ? "指定範圍內沒有時間重疊的已確認行程。"
                : conflicts.stream().map(gap -> "「%s」↔「%s」重疊 %d 分鐘".formatted(
                        gap.first().getTitle(), gap.second().getTitle(), Math.abs(gap.duration().toMinutes())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.SCHEDULE_CONFLICTS_CHECKED, message);
    }

    private IntentResult suggestNextTask(IntentOptions o) {
        Task.Category category = o.category() == null ? null : parseCategory(o.category());
        Optional<Task> found = taskInsightService.recommendNext(category);
        if (found.isEmpty()) {
            return IntentResult.message(IntentResult.Action.NEXT_TASK_SUGGESTED,
                    category == null ? "目前沒有未完成待辦。" : "目前沒有這個分類的未完成待辦。");
        }
        Task task = found.get();
        contextService.rememberTask(task);
        String reason;
        Instant now = Instant.now(clock);
        if (task.getDueAt() != null && task.getDueAt().isBefore(now)) {
            reason = "已逾期";
        } else if (task.getDueAt() != null
                && LocalDate.ofInstant(task.getDueAt(), TAIPEI)
                .equals(LocalDate.ofInstant(now, TAIPEI))) {
            reason = "今天到期";
        } else if (task.getPriority() == TaskPriority.HIGH) {
            reason = "高優先";
        } else if (task.getDueAt() != null) {
            reason = "期限最接近";
        } else {
            reason = "建立時間最早";
        }
        return IntentResult.message(IntentResult.Action.NEXT_TASK_SUGGESTED,
                "建議先做「%s」：%s%s。".formatted(task.getTitle(), reason,
                        task.getDueAt() == null ? "" : "，期限 " + format(task.getDueAt())));
    }

    private IntentResult groupTasksByCategory() {
        var groups = taskInsightService.groupOpenByCategory();
        if (groups.isEmpty()) {
            return IntentResult.message(IntentResult.Action.TASKS_GROUPED_BY_CATEGORY,
                    "目前沒有未完成待辦。 ");
        }
        List<Task> all = groups.values().stream().flatMap(List::stream).toList();
        contextService.rememberTaskList(all);
        String message = groups.entrySet().stream()
                .map(entry -> "%s（%d）\n%s".formatted(categoryLabel(entry.getKey()),
                        entry.getValue().size(), entry.getValue().stream()
                                .map(task -> "- " + task.getTitle())
                                .collect(java.util.stream.Collectors.joining("\n"))))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.TASKS_GROUPED_BY_CATEGORY, message);
    }

    private IntentResult taskProgress(IntentOptions o) {
        boolean week = "WEEK".equalsIgnoreCase(o.filter());
        var progress = taskInsightService.progress(week
                ? TaskInsightService.Scope.THIS_WEEK : TaskInsightService.Scope.TODAY);
        if (progress.total() == 0) {
            return IntentResult.message(IntentResult.Action.TASK_PROGRESS_INFO,
                    week ? "本週沒有可計算進度的待辦。" : "今天沒有可計算進度的待辦。");
        }
        return IntentResult.message(IntentResult.Action.TASK_PROGRESS_INFO,
                "%s進度 %d/%d（%d%%），還有 %d 件。".formatted(
                        week ? "本週" : "今天", progress.completed(), progress.total(),
                        progress.percentage(), progress.remaining()));
    }

    private IntentResult groupTasksByDue() {
        var groups = taskInsightService.groupOpenByDue();
        if (groups.isEmpty()) {
            return IntentResult.message(IntentResult.Action.TASKS_GROUPED_BY_DUE,
                    "目前沒有未完成待辦。 ");
        }
        contextService.rememberTaskList(groups.values().stream().flatMap(List::stream).toList());
        String message = groups.entrySet().stream().map(entry -> {
            String titles = entry.getValue().stream().limit(5)
                    .map(task -> "- " + task.getTitle())
                    .collect(java.util.stream.Collectors.joining("\n"));
            String tail = entry.getValue().size() > 5
                    ? "\n…等 %d 件".formatted(entry.getValue().size()) : "";
            return "%s（%d）\n%s%s".formatted(dueBucketLabel(entry.getKey()),
                    entry.getValue().size(), titles, tail);
        }).collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.TASKS_GROUPED_BY_DUE, message);
    }

    private IntentResult taskLoad(IntentOptions o) {
        boolean nextThreeDays = "NEXT_3_DAYS".equalsIgnoreCase(o.filter());
        var load = taskInsightService.load(nextThreeDays
                ? TaskInsightService.LoadScope.NEXT_THREE_DAYS : TaskInsightService.LoadScope.TODAY);
        return IntentResult.message(IntentResult.Action.TASK_LOAD_INFO,
                "%s %d 件到期，另有 %d 件已逾期；這些事情中 %d 件是高優先。".formatted(
                        nextThreeDays ? "未來三天有" : "今天還有", load.remaining(),
                        load.overdue(), load.highPriority()));
    }

    private IntentResult busiestTaskDay() {
        return taskInsightService.busiestDueDay()
                .map(load -> IntentResult.message(IntentResult.Action.BUSIEST_TASK_DAY_INFO,
                        "未來七天以 %s 的到期待辦最多，共 %d 件。".formatted(load.date(), load.count())))
                .orElseGet(() -> IntentResult.message(IntentResult.Action.BUSIEST_TASK_DAY_INFO,
                        "未來七天沒有設定期限的待辦。"));
    }

    private IntentResult busiestScheduleDay(IntentOptions o) {
        List<ScheduleItem> items = filterSchedules(scheduleInsightService.upcoming(), o);
        return scheduleInsightService.busiestDay(items)
                .map(load -> IntentResult.message(IntentResult.Action.BUSIEST_SCHEDULE_DAY_INFO,
                        "%s 的已排行程最滿，共 %d 個、約 %d 小時 %d 分鐘。".formatted(
                                load.date(), load.count(), load.minutes() / 60, load.minutes() % 60)))
                .orElseGet(() -> IntentResult.message(IntentResult.Action.BUSIEST_SCHEDULE_DAY_INFO,
                        "指定範圍內沒有已確認行程。"));
    }

    private IntentResult longestSchedule(IntentOptions o) {
        List<ScheduleItem> items = filterSchedules(scheduleInsightService.upcoming(), o);
        return scheduleInsightService.longest(items).map(item -> {
            contextService.rememberSchedule(item);
            long minutes = Duration.between(item.getStartAt(), item.getEndAt()).toMinutes();
            return IntentResult.message(IntentResult.Action.LONGEST_SCHEDULE_INFO,
                    "最長的是「%s」，%s 開始，共 %d 小時 %d 分鐘。".formatted(
                            item.getTitle(), format(item.getStartAt()), minutes / 60, minutes % 60));
        }).orElseGet(() -> IntentResult.message(IntentResult.Action.LONGEST_SCHEDULE_INFO,
                "指定範圍內沒有已確認行程。"));
    }

    private IntentResult groupSchedulesByPlace(IntentOptions o) {
        List<ScheduleItem> items = filterSchedules(scheduleInsightService.upcoming(), o);
        if (items.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_PLACE,
                    "指定範圍內沒有已確認行程。 ");
        }
        contextService.rememberScheduleList(items);
        java.util.Map<String, List<ScheduleItem>> groups = new java.util.LinkedHashMap<>();
        items.forEach(item -> {
            String place = item.getPlaceId() == null
                    ? "未設定地點" : placeService.getPlace(item.getPlaceId()).getName();
            groups.computeIfAbsent(place, ignored -> new java.util.ArrayList<>()).add(item);
        });
        String message = groups.entrySet().stream().map(entry -> {
            String lines = entry.getValue().stream().limit(5)
                    .map(item -> "- %s｜%s".formatted(item.getTitle(), format(item.getStartAt())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            String tail = entry.getValue().size() > 5
                    ? "\n…等 %d 個".formatted(entry.getValue().size()) : "";
            return "%s（%d）\n%s%s".formatted(entry.getKey(), entry.getValue().size(), lines, tail);
        }).collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_PLACE, message);
    }

    private IntentResult listShoppingAt(IntentCommand command) {
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        List<Item> items = itemService.listShoppingItemsAt(place.getId());
        String message = items.isEmpty() ? "目前沒有綁在「%s」的待買品項。".formatted(place.getName())
                : "到「%s」要買:%s。".formatted(place.getName(), items.stream().map(Item::getName)
                .collect(java.util.stream.Collectors.joining("、")));
        return IntentResult.message(IntentResult.Action.SHOPPING_BY_PLACE_LISTED, message);
    }

    private IntentResult agendaSummary(IntentOptions o) {
        List<Task> tasks = filterTasks(taskService.listOpenTasks(), o);
        List<ScheduleItem> schedules = filterSchedules(upcomingSchedules(), o);
        long scheduledMinutes = schedules.stream()
                .mapToLong(item -> Duration.between(item.getStartAt(), item.getEndAt()).toMinutes()).sum();
        long dueTasks = tasks.stream().filter(task -> task.getDueAt() != null).count();
        return IntentResult.message(IntentResult.Action.AGENDA_SUMMARY,
                "共有 %d 個行程(約 %d 小時 %d 分鐘)、%d 件待辦,其中 %d 件有期限。".formatted(
                        schedules.size(), scheduledMinutes / 60, scheduledMinutes % 60,
                        tasks.size(), dueTasks));
    }

    private IntentResult resizeSchedule(IntentCommand command, IntentOptions o) {
        ScheduleItem item = scheduleTarget(command, o);
        Instant start = parse(command.startAt());
        if (start == null) start = item.getStartAt();
        Instant end = parse(command.endAt());
        if (end == null && o.durationMinutes() != null && o.durationMinutes() > 0) {
            end = start.plus(Duration.ofMinutes(o.durationMinutes()));
        }
        if (end == null && o.shiftMinutes() != null && o.shiftMinutes() != 0) {
            end = item.getEndAt().plus(Duration.ofMinutes(o.shiftMinutes()));
        }
        if (end == null) throw new IllegalArgumentException("missing schedule duration change");
        var decision = scheduleService.reschedule(item.getId(), start, end);
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleMessage(IntentResult.Action.SCHEDULE_RESIZED,
                "已把「%s」調整為 %s–%s。".formatted(decision.item().getTitle(),
                        format(decision.item().getStartAt()),
                        ZonedDateTime.ofInstant(decision.item().getEndAt(), TAIPEI)
                                .format(DateTimeFormatter.ofPattern("HH:mm"))), decision);
    }

    private IntentResult comparePrices(IntentCommand command) {
        require(command.title(), "title");
        var prices = priceService.compareStores(command.title());
        if (prices.isEmpty()) return IntentResult.message(IntentResult.Action.PRICE_COMPARISON,
                "目前沒有「%s」可比較的店家價格。".formatted(command.title()));
        String lines = prices.stream().map(p -> "%s｜%d 元｜%s".formatted(
                        p.storeName(), p.priceTwd(), p.purchasedAt()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.PRICE_COMPARISON,
                "「%s」歷史最低價比較:\n%s".formatted(command.title(), lines));
    }

    private IntentResult createWeatherReminder(IntentCommand command, IntentOptions o) {
        Instant due = requiredTime(command.dueAt(), "dueAt");
        String title = command.title() == null || command.title().isBlank() ? "帶傘" : command.title();
        Task task = taskService.createTask(title, "下雨才提醒", TaskPriority.NORMAL, due,
                parseCategory(o.category()), Task.Recurrence.NONE, Task.ConditionType.RAIN, null);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.WEATHER_REMINDER_CREATED,
                "已設定 %s 檢查天氣;達到降雨門檻才提醒「%s」。".formatted(format(due), title));
    }

    private IntentResult askTravelTime(IntentCommand command, IntentOptions o) {
        Place to = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        Optional<TravelPlanningService.TravelEstimate> estimate;
        if (o.fromPlaceName() != null && !o.fromPlaceName().isBlank()) {
            Place from = resolvePlace(o.fromPlaceName()).orElseThrow();
            estimate = travelPlanningService.betweenPlaces(from, to, parse(command.startAt()));
        } else {
            estimate = travelPlanningService.fromCurrentLocation(to, parse(command.startAt()));
        }
        return IntentResult.message(IntentResult.Action.TRAVEL_INFO,
                estimate.map(e -> "到「%s」估計約 %d 分鐘。".formatted(to.getName(), e.duration().toMinutes()))
                        .orElse("我還不知道你目前的位置,先回報位置才能估交通時間。"));
    }

    private IntentResult askDepartureTime(IntentCommand command, IntentOptions o) {
        ScheduleItem schedule = command.placeName() == null ? scheduleTarget(command, o) : null;
        Place destination = schedule != null && schedule.getPlaceId() != null
                ? placeService.getPlace(schedule.getPlaceId())
                : resolvePlace(command.placeName()).orElseThrow();
        Instant arrive = command.dueAt() != null ? requiredTime(command.dueAt(), "dueAt")
                : schedule == null ? null : schedule.getStartAt();
        if (arrive == null) throw new IllegalArgumentException("arrival time missing");
        Optional<Instant> leave = travelPlanningService.latestDeparture(destination, arrive);
        return IntentResult.message(IntentResult.Action.TRAVEL_INFO,
                leave.map(at -> "最晚 %s 出發,才能在 %s 前到「%s」。".formatted(
                                format(at), format(arrive), destination.getName()))
                        .orElse("我還不知道你目前的位置,先回報位置才能反推出發時間。"));
    }

    private IntentResult createTrafficWatch(IntentCommand command, IntentOptions o) {
        ScheduleItem schedule = scheduleTarget(command, o);
        if (schedule.getPlaceId() == null) throw new IllegalArgumentException("schedule has no place");
        Place destination = placeService.getPlace(schedule.getPlaceId());
        var estimate = travelPlanningService.fromCurrentLocation(destination, Instant.now(clock))
                .orElseThrow(() -> new IllegalArgumentException("current location missing"));
        int early = positive(o.leadMinutes(), 30);
        Instant checkAt = schedule.getStartAt().minus(estimate.duration()).minus(Duration.ofMinutes(early));
        if (!checkAt.isAfter(Instant.now(clock))) checkAt = Instant.now(clock).plusSeconds(60);
        String payload = travelPlanningService.trafficPayload(destination, estimate.duration().toMinutes());
        Task task = taskService.createTask("路況變差時提早出發:" + schedule.getTitle(),
                null, TaskPriority.HIGH, checkAt, Task.Category.PERSONAL,
                Task.Recurrence.NONE, Task.ConditionType.TRAFFIC, payload);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.TRAFFIC_WATCH_CREATED,
                "已在 %s 檢查前往「%s」的路況;比目前多 10 分鐘以上才提醒。"
                        .formatted(format(checkAt), destination.getName()));
    }

    private IntentResult checkConnection(IntentCommand command, IntentOptions o) {
        require(command.title(), "title");
        require(o.referenceTitle(), "referenceTitle");
        ScheduleItem first = uniqueSchedule(command.title());
        ScheduleItem second = uniqueSchedule(o.referenceTitle());
        if (second.getStartAt().isBefore(first.getStartAt())) {
            ScheduleItem tmp = first; first = second; second = tmp;
        }
        var check = travelPlanningService.checkConnection(first, second);
        String message = check.feasible()
                ? "來得及:空檔 %d 分鐘,預估交通 %d 分鐘。".formatted(check.gap().toMinutes(), check.travel().toMinutes())
                : "來不及:空檔 %d 分鐘,預估交通需要 %d 分鐘。".formatted(check.gap().toMinutes(), check.travel().toMinutes());
        return IntentResult.message(IntentResult.Action.CONNECTION_CHECKED, message);
    }

    private IntentResult setPlanningBuffer(IntentOptions o) {
        int transfer = o.bufferMinutes() == null ? 0 : o.bufferMinutes();
        int meal = o.durationMinutes() == null ? 0 : o.durationMinutes();
        preferenceService.setBuffers(transfer, meal);
        return IntentResult.message(IntentResult.Action.PLANNING_PREFERENCE_SET,
                "之後排程會額外保留交通 %d 分鐘、用餐 %d 分鐘。".formatted(transfer, meal));
    }

    private IntentResult acceptContext() {
        Long id = contextService.scheduleIdAt(null);
        if (id == null) return IntentResult.clarificationNeeded("目前沒有可接受的行程提案。");
        ScheduleItem item = scheduleService.getSchedule(id);
        if (item.getStatus() == ScheduleStatus.PROPOSED) scheduleService.confirmSchedule(id);
        contextService.rememberSchedule(item);
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "好,已確認行程「%s」。".formatted(item.getTitle()));
    }

    private IntentResult shiftContext(IntentCommand command, IntentOptions o) {
        int minutes = positive(o.shiftMinutes(), 60);
        if ("TASK".equalsIgnoreCase(o.referenceKind())) {
            Task task = taskTarget(command, o);
            if (task.getDueAt() == null) return IntentResult.clarificationNeeded("這件待辦目前沒有時間,請直接說要改到何時。");
            Task changed = taskService.changeDueDate(task.getId(), task.getDueAt().plus(Duration.ofMinutes(minutes)));
            contextService.rememberTask(changed);
            return IntentResult.taskRescheduled(changed);
        }
        ScheduleItem item = scheduleTarget(command, o);
        var decision = scheduleService.reschedule(item.getId(),
                item.getStartAt().plus(Duration.ofMinutes(minutes)),
                item.getEndAt().plus(Duration.ofMinutes(minutes)));
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleRescheduled(decision);
    }

    private IntentResult cancelContext(IntentOptions o) {
        if ("TASK".equalsIgnoreCase(o.referenceKind())) {
            Long id = contextService.taskIdAt(o.ordinal());
            if (id == null) return IntentResult.clarificationNeeded("目前沒有可取消的待辦。");
            Task task = taskService.cancelTask(id);
            contextService.rememberTask(task);
            return IntentResult.taskCanceled(task);
        }
        Long id = contextService.scheduleIdAt(o.ordinal());
        if (id == null) return IntentResult.clarificationNeeded("目前沒有可放棄的行程提案。");
        ScheduleItem item = scheduleService.getSchedule(id);
        if (item.getStatus() == ScheduleStatus.PROPOSED) scheduleService.rejectSchedule(id);
        else scheduleService.cancelSchedule(id);
        contextService.rememberSchedule(item);
        return IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "已放棄「%s」。".formatted(item.getTitle()));
    }

    private IntentResult setContextPlace(IntentCommand command, IntentOptions o) {
        Place place = resolvePlace(command.placeName()).orElseGet(() ->
                placeService.createPlace(command.placeName(), null, null, null, null));
        if ("SCHEDULE".equalsIgnoreCase(o.referenceKind())) {
            Long id = contextService.scheduleIdAt(o.ordinal());
            if (id == null) return IntentResult.clarificationNeeded("目前沒有可修改的行程。");
            var decision = scheduleService.changePlace(id, place.getId());
            contextService.rememberSchedule(decision.item());
            return IntentResult.scheduleRescheduled(decision);
        }
        Long id = contextService.taskIdAt(o.ordinal());
        if (id == null) return IntentResult.clarificationNeeded("目前沒有可綁定地點的待辦。");
        TriggerType trigger = parseTrigger(o.triggerType());
        if (!geofenceService.ruleExists(id, place.getId(), trigger)) {
            geofenceService.createRule(id, place.getId(), positive(o.radiusMeters(), 200), trigger);
        }
        Task task = taskService.getTask(id);
        contextService.rememberTask(task);
        return IntentResult.taskPlaceBound(task, place);
    }

    private IntentResult copyContext(IntentCommand command, IntentOptions o) {
        require(command.title(), "title");
        if ("TASK".equalsIgnoreCase(o.referenceKind())) {
            Long id = contextService.taskIdAt(null);
            if (id == null) return IntentResult.clarificationNeeded("目前沒有可複製的待辦。");
            Task source = taskService.getTask(id);
            Task copy = taskService.createTask(command.title(), source.getDescription(), source.getPriority(),
                    parse(command.dueAt()), source.getCategory(), source.getRecurrence(),
                    Task.ConditionType.NONE, null);
            contextService.rememberTask(copy);
            return IntentResult.taskCreated(copy);
        }
        Long id = contextService.scheduleIdAt(null);
        if (id == null) return IntentResult.clarificationNeeded("目前沒有可複製的行程。");
        ScheduleItem source = scheduleService.getSchedule(id);
        Instant start = parse(command.startAt());
        if (start == null) start = source.getStartAt().plus(Duration.ofDays(1));
        Instant end = start.plus(Duration.between(source.getStartAt(), source.getEndAt()));
        var decision = scheduleService.createSchedule(command.title(), start, end, source.getPlaceId(),
                source.getRecurrence() == ScheduleItem.Recurrence.WEEKLY);
        contextService.rememberSchedule(decision.item());
        return IntentResult.scheduleDecided(decision);
    }

    private List<Task> filterTasks(List<Task> source, IntentOptions o) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TAIPEI);
        return source.stream().filter(t -> {
            String filter = o.filter() == null ? "ALL" : o.filter().toUpperCase();
            boolean date = switch (filter) {
                case "TODAY", "WORK_TODAY" -> t.getDueAt() != null
                        && LocalDate.ofInstant(t.getDueAt(), TAIPEI).equals(today);
                case "TOMORROW" -> t.getDueAt() != null
                        && LocalDate.ofInstant(t.getDueAt(), TAIPEI).equals(today.plusDays(1));
                case "UPCOMING_DUE", "HIGH_AND_DUE" -> t.getDueAt() != null && !t.getDueAt().isBefore(now)
                        && t.getDueAt().isBefore(now.plus(Duration.ofDays(3)));
                case "WEEK" -> t.getDueAt() != null && !t.getDueAt().isBefore(now)
                        && !LocalDate.ofInstant(t.getDueAt(), TAIPEI).isAfter(today.plusDays(7));
                case "OVERDUE" -> t.getDueAt() != null && t.getDueAt().isBefore(now);
                case "NO_DUE" -> t.getDueAt() == null;
                case "STALE" -> t.getCreatedAt().isBefore(now.minus(Duration.ofDays(30)));
                case "MONTH" -> t.getDueAt() != null
                        && java.time.YearMonth.from(LocalDate.ofInstant(t.getDueAt(), TAIPEI))
                        .equals(java.time.YearMonth.from(today));
                case "NEXT_MONTH" -> t.getDueAt() != null
                        && java.time.YearMonth.from(LocalDate.ofInstant(t.getDueAt(), TAIPEI))
                        .equals(java.time.YearMonth.from(today).plusMonths(1));
                default -> true;
            };
            Task.Category wanted = parseCategory(o.category());
            boolean category = o.category() == null || wanted == t.getCategory();
            if ("WORK_TODAY".equals(filter)) category = t.getCategory() == Task.Category.WORK;
            boolean priority = !("HIGH_PRIORITY".equals(filter) || "HIGH_AND_DUE".equals(filter))
                    || t.getPriority() == TaskPriority.HIGH;
            boolean recurrence = switch (filter) {
                case "RECURRING" -> t.getRecurrence() != Task.Recurrence.NONE;
                case "PAUSED_RECURRING" -> t.getRecurrence() != Task.Recurrence.NONE
                        && t.isRecurrencePaused();
                default -> true;
            };
            return date && category && priority && recurrence;
        }).toList();
    }

    private List<ScheduleItem> filterSchedules(List<ScheduleItem> source, IntentOptions o) {
        LocalDate today = LocalDate.now(clock.withZone(TAIPEI));
        String filter = o.filter() == null ? "UPCOMING" : o.filter().toUpperCase();
        List<ScheduleItem> filtered = source.stream().filter(s -> switch (filter) {
            case "TODAY", "WORK_TODAY" -> LocalDate.ofInstant(s.getStartAt(), TAIPEI).equals(today);
            case "TOMORROW", "TOMORROW_FIRST" -> LocalDate.ofInstant(s.getStartAt(), TAIPEI).equals(today.plusDays(1));
            case "WEEK" -> !LocalDate.ofInstant(s.getStartAt(), TAIPEI).isAfter(today.plusDays(7));
            case "WEEKEND" -> {
                LocalDate date = LocalDate.ofInstant(s.getStartAt(), TAIPEI);
                yield !date.isAfter(today.plusDays(7))
                        && (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                        || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
            }
            case "WEEKDAY" -> {
                LocalDate date = LocalDate.ofInstant(s.getStartAt(), TAIPEI);
                yield !date.isAfter(today.plusDays(7))
                        && date.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                        && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
            }
            case "MORNING" -> ZonedDateTime.ofInstant(s.getStartAt(), TAIPEI).getHour() < 12;
            case "AFTERNOON" -> {
                int hour = ZonedDateTime.ofInstant(s.getStartAt(), TAIPEI).getHour();
                yield hour >= 12 && hour < 18;
            }
            case "EVENING" -> ZonedDateTime.ofInstant(s.getStartAt(), TAIPEI).getHour() >= 18;
            case "WITH_PLACE" -> s.getPlaceId() != null;
            case "NO_PLACE" -> s.getPlaceId() == null;
            case "RECURRING" -> s.getRecurrence() == ScheduleItem.Recurrence.WEEKLY;
            case "ONE_TIME" -> s.getRecurrence() == ScheduleItem.Recurrence.NONE;
            case "LONG" -> Duration.between(s.getStartAt(), s.getEndAt()).toMinutes() > 120;
            default -> true;
        }).toList();
        return "TOMORROW_FIRST".equals(filter) ? filtered.stream().limit(1).toList() : filtered;
    }

    private List<ScheduleItem> upcomingSchedules() {
        Instant now = Instant.now(clock);
        return scheduleService.listSchedules(ScheduleStatus.CONFIRMED).stream()
                .filter(item -> item.getEndAt().isAfter(now)).toList();
    }

    private Task taskTarget(IntentCommand command, IntentOptions o) {
        if (command.title() != null && !command.title().isBlank()) {
            List<Task> matches = taskService.findOpenTasksMatching(command.title());
            if (matches.size() != 1) throw new IllegalArgumentException("task target is not unique");
            return matches.getFirst();
        }
        Long id = contextService.taskIdAt(o.ordinal());
        if (id == null) throw new IllegalArgumentException("task context missing");
        return taskService.getTask(id);
    }

    private ScheduleItem scheduleTarget(IntentCommand command, IntentOptions o) {
        String title = command.title();
        if ((title == null || title.isBlank()) && o.referenceTitle() != null) title = o.referenceTitle();
        if (title != null && !title.isBlank()) return uniqueSchedule(title);
        Long id = contextService.scheduleIdAt(o.ordinal());
        if (id == null) throw new IllegalArgumentException("schedule context missing");
        return scheduleService.getSchedule(id);
    }

    private ScheduleItem uniqueSchedule(String title) {
        List<ScheduleItem> matches = scheduleService.findReschedulableSchedulesMatching(title);
        if (matches.size() != 1) throw new IllegalArgumentException("schedule target is not unique");
        return matches.getFirst();
    }

    private Optional<Place> resolvePlace(String name) {
        return placeAliasService.resolve(name);
    }

    private List<String> itemNames(IntentCommand command, IntentOptions o) {
        List<String> names = o.itemNames();
        if (names == null || names.isEmpty()) {
            if (command.title() == null || command.title().isBlank()) {
                throw new IllegalArgumentException("itemNames missing");
            }
            names = List.of(command.title());
        }
        return names;
    }

    private static TriggerType parseTrigger(String value) {
        try { return TriggerType.valueOf(value == null ? "ENTER" : value.toUpperCase()); }
        catch (Exception e) { return TriggerType.ENTER; }
    }

    private static Task.Category parseCategory(String value) {
        try { return Task.Category.valueOf(value == null ? "OTHER" : value.toUpperCase()); }
        catch (Exception e) { return Task.Category.OTHER; }
    }

    private static String categoryLabel(Task.Category category) {
        return switch (category) {
            case WORK -> "工作";
            case PERSONAL -> "個人";
            case SHOPPING -> "購物";
            case HEALTH -> "健康";
            case FINANCE -> "財務";
            case OTHER -> "其他";
        };
    }

    private static String dueBucketLabel(TaskInsightService.DueBucket bucket) {
        return switch (bucket) {
            case OVERDUE -> "已逾期";
            case TODAY -> "今天";
            case TOMORROW -> "明天";
            case NEXT_SEVEN_DAYS -> "接下來七天";
            case LATER -> "更晚";
            case NO_DUE -> "沒有期限";
        };
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + field);
    }

    private static Instant requiredTime(String value, String field) {
        Instant parsed = parse(value);
        if (parsed == null) throw new IllegalArgumentException("missing " + field);
        return parsed;
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return java.time.OffsetDateTime.parse(value).toInstant(); }
        catch (Exception e) { throw new IllegalArgumentException("bad time: " + value); }
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private static String clarificationFor(String detail) {
        if (detail != null && detail.contains("current location")) {
            return "我還不知道你目前的位置,先傳位置給我才能估算。";
        }
        if (detail != null && detail.contains("unknown destination")) {
            return "我找不到目的地,請說完整地點名稱或先建立地點。";
        }
        if (detail != null && detail.contains("not unique")) {
            return "有不只一筆符合,請再補日期、時間或完整名稱,我才不會改錯。";
        }
        if (detail != null && detail.contains("context")) {
            return "目前沒有可承接的上一筆內容,請直接說待辦或行程名稱。";
        }
        return "這句還缺少可執行的資訊,請補上名稱、日期時間或地點。";
    }
}
