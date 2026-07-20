package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.family.application.FamilyMessageService;
import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.planner.application.NearbySuggestionService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Executes deterministic place resolution, task-place binding and geofence commands. */
@Component
public final class PlaceIntentHandler implements IntentHandler {

    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.ASK_PLACE,
            IntentCommand.Type.CREATE_PLACE,
            IntentCommand.Type.BIND_TASK_PLACE,
            IntentCommand.Type.ASK_TASK_PLACE,
            IntentCommand.Type.SUGGEST_NEARBY,
            IntentCommand.Type.SET_PLACE_ALIAS,
            IntentCommand.Type.LIST_LOCATION_TASKS,
            IntentCommand.Type.ASK_PLACE_TASKS,
            IntentCommand.Type.ASK_TASK_GEOFENCE,
            IntentCommand.Type.UPDATE_TASK_GEOFENCE,
            IntentCommand.Type.REMOVE_TASK_PLACE);

    private final TaskService taskService;
    private final PlaceAliasService placeAliasService;
    private final PlaceService placeService;
    private final GeofenceRuleService geofenceService;
    private final NearbySuggestionService nearbySuggestionService;
    private final ConversationContextService contextService;
    private final FamilyMessageService familyMessageService;
    private final int bindRadiusMeters;

    public PlaceIntentHandler(
            TaskService taskService,
            PlaceAliasService placeAliasService,
            PlaceService placeService,
            GeofenceRuleService geofenceService,
            NearbySuggestionService nearbySuggestionService,
            ConversationContextService contextService,
            FamilyMessageService familyMessageService,
            @Value("${app.knowledge.auto-bind-radius-meters:200}") int bindRadiusMeters) {
        this.taskService = taskService;
        this.placeAliasService = placeAliasService;
        this.placeService = placeService;
        this.geofenceService = geofenceService;
        this.nearbySuggestionService = nearbySuggestionService;
        this.contextService = contextService;
        this.familyMessageService = familyMessageService;
        this.bindRadiusMeters = bindRadiusMeters;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        IntentOptions options = command.safeOptions();
        return switch (command.type()) {
            case ASK_PLACE -> askPlace(command);
            case CREATE_PLACE -> createPlace(command);
            case BIND_TASK_PLACE -> bindTaskPlace(command, options);
            case ASK_TASK_PLACE -> askTaskPlace(command);
            case SUGGEST_NEARBY -> suggestNearby(command);
            case SET_PLACE_ALIAS, LIST_LOCATION_TASKS, ASK_PLACE_TASKS,
                    ASK_TASK_GEOFENCE, UPDATE_TASK_GEOFENCE,
                    REMOVE_TASK_PLACE -> handleLifestyleCommand(command, options);
            default -> throw new IllegalArgumentException(
                    "unsupported place intent type " + command.type());
        };
    }

    private IntentResult handleLifestyleCommand(IntentCommand command, IntentOptions options) {
        try {
            return switch (command.type()) {
                case SET_PLACE_ALIAS -> setPlaceAlias(command, options);
                case LIST_LOCATION_TASKS -> listLocationTasks(options);
                case ASK_PLACE_TASKS -> askPlaceTasks(command, options);
                case ASK_TASK_GEOFENCE -> askTaskGeofence(command, options);
                case UPDATE_TASK_GEOFENCE -> updateTaskGeofence(command, options);
                case REMOVE_TASK_PLACE -> removeTaskPlace(command, options);
                default -> throw new IllegalArgumentException(
                        "unsupported lifestyle place intent type " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult askPlace(IntentCommand command) {
        require(command.placeName(), "placeName");
        return resolvePlace(command.placeName())
                .map(this::placeInfo)
                .orElseGet(() -> IntentResult.clarificationNeeded(
                        "我沒有叫「%s」的地點紀錄,說「建立地點:%s」我就去 Google 查來存。"
                                .formatted(command.placeName(), command.placeName())));
    }

    private IntentResult createPlace(IntentCommand command) {
        require(command.placeName(), "placeName");
        Optional<Place> existing = resolvePlace(command.placeName());
        return existing.map(this::placeInfo).orElseGet(() -> IntentResult.placeCreated(
                placeService.createPlace(command.placeName(), null, null, null, null)));
    }

    private IntentResult bindTaskPlace(IntentCommand command, IntentOptions options) {
        require(command.placeName(), "placeName");
        TaskMatch match = matchOpenTask(command, "綁");
        if (match.failure() != null) {
            return match.failure();
        }
        Place place = resolvePlace(command.placeName()).orElseGet(() ->
                placeService.createPlace(command.placeName(), null, null, null, null));
        TriggerType trigger = parseTrigger(options.triggerType());
        if (!geofenceService.ruleExists(match.task().getId(), place.getId(), trigger)) {
            geofenceService.createRule(match.task().getId(), place.getId(),
                    positive(options.radiusMeters(), bindRadiusMeters), trigger);
        }
        return IntentResult.taskPlaceBound(match.task(), place);
    }

    private IntentResult askTaskPlace(IntentCommand command) {
        TaskMatch match = matchOpenTask(command, "查");
        if (match.failure() != null) {
            return match.failure();
        }
        List<Place> places = geofenceService.listRulesForTask(match.task().getId()).stream()
                .map(rule -> placeService.getPlace(rule.getPlaceId()))
                .distinct()
                .toList();
        return IntentResult.taskPlaceInfo(match.task(), places);
    }

    private IntentResult suggestNearby(IntentCommand command) {
        if (command.windowHours() != null && command.windowHours() > 0) {
            return IntentResult.suggestionMade(nearbySuggestionService.suggest(
                    java.time.Duration.ofHours(command.windowHours())));
        }
        long defaultHours = nearbySuggestionService.defaultWindow().toHours();
        return IntentResult.suggestionMade(
                "「待會」你抓多久?跟我說「看2小時」我就用那個範圍重算;先用 %d 小時給你參考:\n\n%s"
                        .formatted(defaultHours, nearbySuggestionService.suggest()));
    }

    private IntentResult setPlaceAlias(IntentCommand command, IntentOptions options) {
        require(options.alias(), "alias");
        Place place = resolvePlace(command.placeName()).orElseGet(() ->
                placeService.createPlace(command.placeName(), null, null, null, null));
        placeAliasService.remember(options.alias(), place.getId());
        contextService.rememberPlace(place.getId());
        return IntentResult.message(IntentResult.Action.PLACE_ALIAS_SET,
                "記住了,「%s」就是「%s」。".formatted(options.alias(), place.getName()));
    }

    private IntentResult listLocationTasks(IntentOptions options) {
        Set<Long> boundIds = geofenceService.listAllRules().stream()
                .map(rule -> rule.getTaskId()).collect(java.util.stream.Collectors.toSet());
        boolean unbound = "UNBOUND".equalsIgnoreCase(options.filter());
        List<Task> tasks = taskService.listOpenTasks().stream()
                .filter(task -> unbound != boundIds.contains(task.getId()))
                .toList();
        contextService.rememberTaskList(tasks);
        String message = tasks.isEmpty() ? (unbound ? "所有未完成待辦都已有地點規則。" : "目前沒有地點提醒待辦。")
                : (unbound ? "尚未設定地點規則的待辦:\n" : "地點提醒待辦:\n")
                        + tasks.stream().map(task -> "「%s」".formatted(task.getTitle()))
                                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.LOCATION_TASKS_LISTED, message);
    }

    private IntentResult askPlaceTasks(IntentCommand command, IntentOptions options) {
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        TriggerType trigger = options.triggerType() == null ? null : parseTrigger(options.triggerType());
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

    private IntentResult askTaskGeofence(IntentCommand command, IntentOptions options) {
        Task task = taskTarget(command, options);
        List<String> lines = geofenceService.listRulesForTask(task.getId()).stream()
                .map(rule -> "%s｜%s｜半徑 %dm".formatted(
                        placeService.getPlace(rule.getPlaceId()).getName(),
                        rule.getTriggerType(), rule.getRadiusMeters()))
                .toList();
        return IntentResult.message(IntentResult.Action.TASK_GEOFENCE_INFO,
                lines.isEmpty() ? "「%s」目前沒有地點提醒。".formatted(task.getTitle())
                        : "「%s」的地點提醒:\n%s".formatted(task.getTitle(), String.join("\n", lines)));
    }

    private IntentResult updateTaskGeofence(IntentCommand command, IntentOptions options) {
        Task task = taskTarget(command, options);
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        TriggerType trigger = options.triggerType() == null ? null : parseTrigger(options.triggerType());
        if (options.radiusMeters() == null && trigger == null) {
            throw new IllegalArgumentException("missing geofence changes");
        }
        var rule = geofenceService.updateUniqueRule(
                task.getId(), place.getId(), options.radiusMeters(), trigger);
        return IntentResult.message(IntentResult.Action.TASK_GEOFENCE_UPDATED,
                "已把「%s」在「%s」的提醒改為 %s、半徑 %d 公尺。".formatted(
                        task.getTitle(), place.getName(), rule.getTriggerType(), rule.getRadiusMeters()));
    }

    private IntentResult removeTaskPlace(IntentCommand command, IntentOptions options) {
        Task task = taskTarget(command, options);
        Place place = resolvePlace(command.placeName()).orElseThrow(() ->
                new IllegalArgumentException("unknown destination place"));
        geofenceService.removeUniqueRule(task.getId(), place.getId());
        return IntentResult.message(IntentResult.Action.TASK_PLACE_REMOVED,
                "已移除「%s」在「%s」的地點提醒;待辦本身仍保留。".formatted(
                        task.getTitle(), place.getName()));
    }

    private IntentResult placeInfo(Place place) {
        String guidance = familyMessageService.placeGuidance(place.getName()).orElse(null);
        return IntentResult.placeInfo(place, guidance);
    }

    private Optional<Place> resolvePlace(String name) {
        return placeAliasService.resolve(name);
    }

    private Task taskTarget(IntentCommand command, IntentOptions options) {
        if (command.title() != null && !command.title().isBlank()) {
            List<Task> matches = taskService.findOpenTasksMatching(command.title());
            if (matches.size() != 1) {
                throw new IllegalArgumentException("task target is not unique");
            }
            return matches.getFirst();
        }
        Long id = contextService.taskIdAt(options.ordinal());
        if (id == null) {
            throw new IllegalArgumentException("task context missing");
        }
        return taskService.getTask(id);
    }

    private TaskMatch matchOpenTask(IntentCommand command, String actionVerb) {
        if (command.safeOptions().ordinal() != null) {
            Long id = contextService.taskIdAt(command.safeOptions().ordinal());
            if (id != null) {
                return openContextTask(id, actionVerb);
            }
        }
        if (command.title() != null && !command.title().isBlank()) {
            List<Task> matches = taskService.findOpenTasksMatching(command.title());
            if (matches.isEmpty()) {
                return new TaskMatch(null, IntentResult.clarificationNeeded(
                        "找不到跟「%s」有關的未完成任務。".formatted(command.title())));
            }
            if (matches.size() > 1) {
                contextService.rememberTaskList(matches);
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
            return new TaskMatch(matches.getFirst(), null);
        }
        Long id = contextService.taskIdAt(command.safeOptions().ordinal());
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

    private static TriggerType parseTrigger(String value) {
        try {
            return TriggerType.valueOf(value == null ? "ENTER" : value.toUpperCase());
        } catch (Exception exception) {
            return TriggerType.ENTER;
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

    private record TaskMatch(Task task, IntentResult failure) {
    }
}
