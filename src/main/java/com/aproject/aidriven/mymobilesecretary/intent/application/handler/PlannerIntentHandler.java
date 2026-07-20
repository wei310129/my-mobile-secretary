package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PlanningPreferenceService;
import com.aproject.aidriven.mymobilesecretary.planner.application.FeasibilityService;
import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import com.aproject.aidriven.mymobilesecretary.planner.application.LocalizedWeatherService;
import com.aproject.aidriven.mymobilesecretary.planner.application.RouteSuggestionService;
import com.aproject.aidriven.mymobilesecretary.planner.application.TravelPlanningService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Executes deterministic free-slot, route, weather and transport planning commands. */
@Component
@RequiredArgsConstructor
public final class PlannerIntentHandler implements IntentHandler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.SUGGEST_FREE_SLOT,
            IntentCommand.Type.SUGGEST_ROUTE_TASKS,
            IntentCommand.Type.ASK_WEATHER,
            IntentCommand.Type.CREATE_WEATHER_REMINDER,
            IntentCommand.Type.ASK_TRAVEL_TIME,
            IntentCommand.Type.ASK_DEPARTURE_TIME,
            IntentCommand.Type.CREATE_TRAFFIC_WATCH,
            IntentCommand.Type.CHECK_FEASIBILITY,
            IntentCommand.Type.SET_PLANNING_BUFFER);

    private final TaskService taskService;
    private final ScheduleService scheduleService;
    private final PlaceAliasService placeAliasService;
    private final PlaceService placeService;
    private final FreeSlotService freeSlotService;
    private final FeasibilityService feasibilityService;
    private final RouteSuggestionService routeSuggestionService;
    private final TravelPlanningService travelPlanningService;
    private final LocalizedWeatherService localizedWeatherService;
    private final PlanningPreferenceService preferenceService;
    private final ConversationContextService contextService;
    private final Clock clock;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        try {
            IntentOptions options = command.safeOptions();
            return switch (command.type()) {
                case SUGGEST_FREE_SLOT -> suggestFreeSlot(command, options);
                case SUGGEST_ROUTE_TASKS -> suggestRoute(command);
                case ASK_WEATHER -> IntentResult.message(IntentResult.Action.WEATHER_INFO,
                        localizedWeatherService.describeCurrentForecast());
                case CREATE_WEATHER_REMINDER -> createWeatherReminder(command, options);
                case ASK_TRAVEL_TIME -> askTravelTime(command, options);
                case ASK_DEPARTURE_TIME -> askDepartureTime(command, options);
                case CREATE_TRAFFIC_WATCH -> createTrafficWatch(command, options);
                case CHECK_FEASIBILITY -> checkConnection(command, options);
                case SET_PLANNING_BUFFER -> setPlanningBuffer(options);
                default -> throw new IllegalArgumentException(
                        "unsupported planner intent type " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult suggestFreeSlot(IntentCommand command, IntentOptions options) {
        Instant from = parse(command.startAt());
        Instant until = parse(command.endAt());
        if ("FREEST_DAY".equalsIgnoreCase(options.filter())) {
            FreeSlotService.DayLoad day = freeSlotService.freestDay(from, until);
            return IntentResult.message(IntentResult.Action.FREE_SLOTS_SUGGESTED,
                    "%s最空,目前已排約 %d 分鐘。".formatted(day.date(), day.busy().toMinutes()));
        }
        List<FreeSlotService.Slot> slots = freeSlotService.suggest(from, until,
                Duration.ofMinutes(positive(options.durationMinutes(), 60)), options.timeOfDay());
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

    private IntentResult suggestRoute(IntentCommand command) {
        Place destination = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        return IntentResult.message(IntentResult.Action.ROUTE_SUGGESTED,
                routeSuggestionService.suggest(destination));
    }

    private IntentResult createWeatherReminder(IntentCommand command, IntentOptions options) {
        Instant due = requiredTime(command.dueAt(), "dueAt");
        String title = command.title() == null || command.title().isBlank() ? "帶傘" : command.title();
        Task task = taskService.createTask(title, "下雨才提醒", TaskPriority.NORMAL, due,
                parseCategory(options.category()), Task.Recurrence.NONE, Task.ConditionType.RAIN, null);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.WEATHER_REMINDER_CREATED,
                "已設定 %s 檢查天氣;達到降雨門檻才提醒「%s」。".formatted(format(due), title));
    }

    private IntentResult askTravelTime(IntentCommand command, IntentOptions options) {
        Place to = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        Optional<TravelPlanningService.TravelEstimate> estimate;
        if (options.fromPlaceName() != null && !options.fromPlaceName().isBlank()) {
            Place from = resolvePlace(options.fromPlaceName()).orElseThrow();
            estimate = travelPlanningService.betweenPlaces(from, to, parse(command.startAt()));
        } else {
            estimate = travelPlanningService.fromCurrentLocation(to, parse(command.startAt()));
        }
        return IntentResult.message(IntentResult.Action.TRAVEL_INFO,
                estimate.map(value -> "到「%s」估計約 %d 分鐘。".formatted(
                                to.getName(), value.duration().toMinutes()))
                        .orElse("我還不知道你目前的位置,先回報位置才能估交通時間。"));
    }

    private IntentResult askDepartureTime(IntentCommand command, IntentOptions options) {
        ScheduleItem schedule = command.placeName() == null ? scheduleTarget(command, options) : null;
        Place destination = schedule != null && schedule.getPlaceId() != null
                ? placeService.getPlace(schedule.getPlaceId())
                : resolvePlace(command.placeName()).orElseThrow();
        Instant arrive = command.dueAt() != null ? requiredTime(command.dueAt(), "dueAt")
                : schedule == null ? null : schedule.getStartAt();
        if (arrive == null) {
            throw new IllegalArgumentException("arrival time missing");
        }
        Duration preferredBuffer = preferenceService.extraTransferBuffer();
        int explicitBuffer = options.bufferMinutes() == null
                ? preferredBuffer == null ? 0 : Math.toIntExact(preferredBuffer.toMinutes())
                : options.bufferMinutes();
        if (explicitBuffer < 0 || explicitBuffer > 240) {
            throw new IllegalArgumentException("arrival buffer must be between 0 and 240 minutes");
        }
        Duration extraBuffer = Duration.ofMinutes(explicitBuffer);
        Optional<TravelPlanningService.DeparturePlan> plan;
        String origin;
        if (options.fromPlaceName() != null && !options.fromPlaceName().isBlank()) {
            Place from = resolvePlace(options.fromPlaceName()).orElseThrow(() ->
                    new IllegalArgumentException("unknown origin place"));
            plan = Optional.of(travelPlanningService.latestDepartureBetweenPlaces(
                    from, destination, arrive, extraBuffer));
            origin = "從「%s」".formatted(from.getName());
        } else {
            plan = travelPlanningService.latestDepartureFromCurrentLocation(
                    destination, arrive, extraBuffer);
            origin = "從目前位置";
        }
        return IntentResult.message(IntentResult.Action.TRAVEL_INFO,
                plan.map(value -> departureMessage(origin, destination, value))
                        .orElse("我還不知道你目前的位置,先回報位置才能反推出發時間。"));
    }

    private static String departureMessage(
            String origin, Place destination, TravelPlanningService.DeparturePlan plan) {
        String extra = plan.extraArrivalBuffer().isZero()
                ? ""
                : "，另保留 %d 分鐘停車／抵達緩衝".formatted(
                        plan.extraArrivalBuffer().toMinutes());
        return "%s最晚 %s 出發，交通估計 %d 分鐘（含系統基本轉場緩衝 %d 分鐘）%s，才能在 %s 前到「%s」。"
                .formatted(origin, format(plan.departAt()), plan.travelDuration().toMinutes(),
                        plan.includedTransferBuffer().toMinutes(), extra,
                        format(plan.arriveBy()), destination.getName());
    }

    private IntentResult createTrafficWatch(IntentCommand command, IntentOptions options) {
        ScheduleItem schedule = scheduleTarget(command, options);
        if (schedule.getPlaceId() == null) {
            throw new IllegalArgumentException("schedule has no place");
        }
        Place destination = placeService.getPlace(schedule.getPlaceId());
        var estimate = travelPlanningService.fromCurrentLocation(destination, Instant.now(clock))
                .orElseThrow(() -> new IllegalArgumentException("current location missing"));
        int early = positive(options.leadMinutes(), 30);
        Instant checkAt = schedule.getStartAt().minus(estimate.duration()).minus(Duration.ofMinutes(early));
        if (!checkAt.isAfter(Instant.now(clock))) {
            checkAt = Instant.now(clock).plusSeconds(60);
        }
        String payload = travelPlanningService.trafficPayload(
                destination, estimate.duration().toMinutes());
        Task task = taskService.createTask("路況變差時提早出發:" + schedule.getTitle(),
                null, TaskPriority.HIGH, checkAt, Task.Category.PERSONAL,
                Task.Recurrence.NONE, Task.ConditionType.TRAFFIC, payload);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.TRAFFIC_WATCH_CREATED,
                "已在 %s 檢查前往「%s」的路況;比目前多 10 分鐘以上才提醒。"
                        .formatted(format(checkAt), destination.getName()));
    }

    private IntentResult checkConnection(IntentCommand command, IntentOptions options) {
        if (command.startAt() != null
                && (command.endAt() != null || options.durationMinutes() != null)) {
            return checkHypotheticalWindow(command, options);
        }
        require(command.title(), "title");
        require(options.referenceTitle(), "referenceTitle");
        ScheduleItem first = uniqueSchedule(command.title());
        ScheduleItem second = uniqueSchedule(options.referenceTitle());
        if (second.getStartAt().isBefore(first.getStartAt())) {
            ScheduleItem temporary = first;
            first = second;
            second = temporary;
        }
        var check = travelPlanningService.checkConnection(first, second);
        String message = check.feasible()
                ? "來得及:空檔 %d 分鐘,預估交通 %d 分鐘。".formatted(
                        check.gap().toMinutes(), check.travel().toMinutes())
                : "來不及:空檔 %d 分鐘,預估交通需要 %d 分鐘。".formatted(
                        check.gap().toMinutes(), check.travel().toMinutes());
        return IntentResult.message(IntentResult.Action.CONNECTION_CHECKED, message);
    }

    private IntentResult checkHypotheticalWindow(IntentCommand command, IntentOptions options) {
        Instant start = requiredTime(command.startAt(), "startAt");
        Instant end = command.endAt() == null
                ? start.plus(Duration.ofMinutes(positive(options.durationMinutes(), 60)))
                : requiredTime(command.endAt(), "endAt");
        int preparationMinutes = nonNegative(options.leadMinutes(), "preparation");
        int afterTravelMinutes = nonNegative(options.bufferMinutes(), "after travel");
        String title = command.title() == null || command.title().isBlank()
                ? "假設行程" : command.title().strip();
        var analysis = feasibilityService.analyzeHypotheticalWindow(
                title, start, end, Duration.ofMinutes(preparationMinutes),
                Duration.ofMinutes(afterTravelMinutes));
        String segments = "整段需保留 %s–%s（準備 %d 分鐘 %s–%s；「%s」%s–%s；後續交通 %d 分鐘 %s–%s）。"
                .formatted(format(analysis.windowStart()), time(analysis.windowEnd()),
                        preparationMinutes, format(analysis.windowStart()), time(start),
                        title, format(start), time(end), afterTravelMinutes,
                        format(end), time(analysis.windowEnd()));
        String conflicts = analysis.feasible()
                ? "三個區段都沒有撞到已確認行程。"
                : analysis.conflicts().stream()
                        .map(conflict -> "%s與「%s」在 %s–%s 撞期。".formatted(
                                segmentLabel(conflict.segment()), conflict.existingTitle(),
                                format(conflict.overlapStart()), time(conflict.overlapEnd())))
                        .collect(java.util.stream.Collectors.joining(" "));
        return IntentResult.message(IntentResult.Action.CONNECTION_CHECKED,
                "只做檢查，未建立行程。" + segments + conflicts);
    }

    private static int nonNegative(Integer value, String field) {
        int minutes = value == null ? 0 : value;
        if (minutes < 0 || minutes > 240) {
            throw new IllegalArgumentException(field + " must be between 0 and 240 minutes");
        }
        return minutes;
    }

    private static String segmentLabel(FeasibilityService.HypotheticalSegment segment) {
        return switch (segment) {
            case PREPARATION -> "準備段";
            case MAIN -> "主行程";
            case AFTER_TRAVEL -> "後續交通段";
        };
    }

    private IntentResult setPlanningBuffer(IntentOptions options) {
        int transfer = options.bufferMinutes() == null ? 0 : options.bufferMinutes();
        int meal = options.durationMinutes() == null ? 0 : options.durationMinutes();
        preferenceService.setBuffers(transfer, meal);
        return IntentResult.message(IntentResult.Action.PLANNING_PREFERENCE_SET,
                "之後排程會額外保留交通 %d 分鐘、用餐 %d 分鐘。".formatted(transfer, meal));
    }

    private ScheduleItem scheduleTarget(IntentCommand command, IntentOptions options) {
        String title = command.title();
        if ((title == null || title.isBlank()) && options.referenceTitle() != null) {
            title = options.referenceTitle();
        }
        if (title != null && !title.isBlank()) {
            return uniqueSchedule(title);
        }
        Long id = contextService.scheduleIdAt(options.ordinal());
        if (id == null) {
            throw new IllegalArgumentException("schedule context missing");
        }
        return scheduleService.getSchedule(id);
    }

    private ScheduleItem uniqueSchedule(String title) {
        List<ScheduleItem> matches = scheduleService.findReschedulableSchedulesMatching(title);
        if (matches.size() != 1) {
            throw new IllegalArgumentException("schedule target is not unique");
        }
        return matches.getFirst();
    }

    private Optional<Place> resolvePlace(String name) {
        return placeAliasService.resolve(name);
    }

    private static Task.Category parseCategory(String value) {
        try {
            return Task.Category.valueOf(value == null ? "OTHER" : value.toUpperCase());
        } catch (Exception exception) {
            return Task.Category.OTHER;
        }
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }

    private static Instant requiredTime(String value, String field) {
        Instant parsed = parse(value);
        if (parsed == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        return parsed;
    }

    private static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception exception) {
            throw new IllegalArgumentException("bad time: " + value);
        }
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private static String time(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}
