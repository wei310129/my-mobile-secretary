package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.DeferredTaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.FlexibleDayTaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.DeferredTask;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Executes task mutations and their deterministic target/safety checks. */
@Component
public final class TaskMutationIntentHandler implements IntentHandler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.CREATE_TASK,
            IntentCommand.Type.CREATE_FLEXIBLE_DAY_TASK,
            IntentCommand.Type.COMPLETE_TASK,
            IntentCommand.Type.CANCEL_TASK,
            IntentCommand.Type.CANCEL_ALL_TASKS,
            IntentCommand.Type.RESCHEDULE_TASK,
            IntentCommand.Type.CONVERT_TASK_TO_SCHEDULE_REMINDER,
            IntentCommand.Type.CONVERT_TASK_TO_TODO,
            IntentCommand.Type.UPDATE_TASK,
            IntentCommand.Type.PAUSE_RECURRING_TASK,
            IntentCommand.Type.RESUME_RECURRING_TASK,
            IntentCommand.Type.SKIP_RECURRING_OCCURRENCE);

    private final TaskService taskService;
    private final DeferredTaskService deferredTaskService;
    private final ScheduleService scheduleService;
    private final ConversationContextService contextService;
    private final PlaceAliasService placeAliasService;
    private final PlaceService placeService;
    private final GeofenceRuleService geofenceService;
    private final int bindRadiusMeters;
    private FlexibleDayTaskService flexibleDayTaskService;

    public TaskMutationIntentHandler(
            TaskService taskService,
            DeferredTaskService deferredTaskService,
            ScheduleService scheduleService,
            ConversationContextService contextService,
            PlaceAliasService placeAliasService,
            PlaceService placeService,
            GeofenceRuleService geofenceService,
            @Value("${app.knowledge.auto-bind-radius-meters:200}") int bindRadiusMeters) {
        this.taskService = taskService;
        this.deferredTaskService = deferredTaskService;
        this.scheduleService = scheduleService;
        this.contextService = contextService;
        this.placeAliasService = placeAliasService;
        this.placeService = placeService;
        this.geofenceService = geofenceService;
        this.bindRadiusMeters = bindRadiusMeters;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setFlexibleDayTaskService(FlexibleDayTaskService service) {
        this.flexibleDayTaskService = service;
    }

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        return switch (command.type()) {
            case CREATE_TASK -> createTask(command);
            case CREATE_FLEXIBLE_DAY_TASK -> createFlexibleDayTask(command);
            case COMPLETE_TASK -> completeTask(command);
            case CANCEL_TASK -> cancelTask(command);
            case CANCEL_ALL_TASKS -> IntentResult.allTasksCanceled(taskService.cancelAllOpenTasks());
            case RESCHEDULE_TASK -> rescheduleTask(command);
            case CONVERT_TASK_TO_SCHEDULE_REMINDER -> convertToScheduleReminder(command);
            case CONVERT_TASK_TO_TODO -> convertToTodo(command);
            case UPDATE_TASK, PAUSE_RECURRING_TASK, RESUME_RECURRING_TASK,
                    SKIP_RECURRING_OCCURRENCE -> handleLifestyleMutation(command);
            default -> throw new IllegalArgumentException(
                    "unsupported task mutation intent type " + command.type());
        };
    }

    private IntentResult createTask(IntentCommand command) {
        require(command.title(), "title");
        boolean duplicate = taskService.findOpenTasksMatching(command.title()).stream()
                .anyMatch(task -> task.getTitle().equalsIgnoreCase(command.title().strip()));
        if (duplicate) {
            return IntentResult.clarificationNeeded(
                    "已經有「%s」這件未完成待辦了,不再重複建立;要改時間或地點直接說。"
                            .formatted(command.title()));
        }
        IntentOptions options = command.safeOptions();
        if ("AFTER_TASK_COMPLETION".equalsIgnoreCase(options.referenceKind())) {
            return deferTask(command, options);
        }
        Task task = taskService.createTask(
                command.title(), null, parsePriority(command.priority()),
                parseTime(command.dueAt()), parseCategory(options.category()),
                parseRecurrence(options.recurrence()), parseCondition(options.condition()), null);
        Place taskPlace = null;
        if (command.placeName() != null && !command.placeName().isBlank()) {
            taskPlace = placeAliasService.resolve(command.placeName())
                    .orElseGet(() -> placeService.createPlace(
                            command.placeName(), null, null, null, null));
            TriggerType trigger = parseTrigger(options.triggerType());
            if (!geofenceService.ruleExists(task.getId(), taskPlace.getId(), trigger)) {
                geofenceService.createRule(task.getId(), taskPlace.getId(),
                        positive(options.radiusMeters(), bindRadiusMeters), trigger);
            }
        }
        return IntentResult.taskCreated(task, options.clarificationQuestion(), taskPlace);
    }

    private IntentResult createFlexibleDayTask(IntentCommand command) {
        require(command.title(), "title");
        Instant targetInstant = parseTime(command.startAt());
        if (targetInstant == null) {
            throw new IllegalArgumentException("flexible day task missing target date");
        }
        if (flexibleDayTaskService == null) {
            throw new IllegalArgumentException("flexible day task service unavailable");
        }
        boolean duplicate = taskService.findOpenTasksMatching(command.title()).stream()
                .anyMatch(task -> task.getTitle().equalsIgnoreCase(command.title().strip()));
        if (duplicate) {
            return IntentResult.clarificationNeeded(
                    "已經有「%s」這件未完成待辦了，不再重複建立。".formatted(command.title()));
        }
        java.time.LocalDate targetDate = targetInstant.atZone(TAIPEI).toLocalDate();
        var planned = flexibleDayTaskService.plan(command.title(),
                command.safeOptions().description(), targetDate, targetDate, null,
                com.aproject.aidriven.mymobilesecretary.reminder.domain.FlexibleDayTaskPlan
                        .SourceKind.USER_REQUEST);
        return IntentResult.message(IntentResult.Action.TASK_CREATED,
                "已建立「%s」的 %s 全天彈性待辦，不會用 00:00–24:00 占滿行事曆。\n"
                        .formatted(command.title(), targetDate)
                        + "我依目前已知行程選擇 %s 提醒；若你先回報完成，後續提醒會停止。"
                                .formatted(format(planned.plan().getRemindAt())));
    }

    private IntentResult deferTask(IntentCommand command, IntentOptions options) {
        require(options.referenceTitle(), "referenceTitle");
        if (command.dueAt() != null || command.placeName() != null
                || options.recurrence() != null || options.condition() != null) {
            throw new IllegalArgumentException(
                    "dependent task supports completion offset only; absolute time, place, recurrence and condition must be empty");
        }
        int offsetMinutes = options.shiftMinutes() == null ? 0 : options.shiftMinutes();
        if (offsetMinutes < 0) {
            throw new IllegalArgumentException("dependent task shiftMinutes must not be negative");
        }
        DeferredTask deferred = deferredTaskService.deferUntilTaskCompleted(
                options.referenceTitle(), command.title(), options.description(),
                parsePriority(command.priority()), offsetMinutes);
        return IntentResult.taskDeferred(
                deferred.getTitle(), options.referenceTitle(), deferred.getDueOffsetMinutes());
    }

    private IntentResult completeTask(IntentCommand command) {
        TaskMatch match = matchOpenTask(command, "劃");
        return match.failure() != null ? match.failure()
                : IntentResult.taskCompleted(taskService.confirmTask(match.task().getId()));
    }

    private IntentResult cancelTask(IntentCommand command) {
        TaskMatch match = matchOpenTask(command, "取消");
        return match.failure() != null ? match.failure()
                : IntentResult.taskCanceled(taskService.cancelTask(match.task().getId()));
    }

    private IntentResult rescheduleTask(IntentCommand command) {
        Instant newDueAt = parseTime(command.dueAt());
        if (newDueAt == null) {
            throw new IllegalArgumentException("reschedule missing dueAt");
        }
        TaskMatch match = matchOpenTask(command, "改");
        return match.failure() != null ? match.failure()
                : IntentResult.taskRescheduled(
                        taskService.changeDueDate(match.task().getId(), newDueAt));
    }

    private IntentResult convertToScheduleReminder(IntentCommand command) {
        Instant dueAt = parseTime(command.dueAt());
        if (dueAt == null) {
            return IntentResult.clarificationNeeded(
                    "要轉成行程提醒，請先提供明確的提醒日期與時間；我不會自行猜測。 ");
        }
        TaskMatch match = matchOpenTask(command, "轉換");
        if (match.failure() != null) return match.failure();
        var decision = com.aproject.aidriven.mymobilesecretary.planning.domain
                .PlanningItemTransitionPolicy.assess(
                        com.aproject.aidriven.mymobilesecretary.planning.application
                                .PlanningItemClassifier.classify(match.task()),
                        com.aproject.aidriven.mymobilesecretary.planning.domain
                                .PlanningItemType.SCHEDULE_REMINDER,
                        new com.aproject.aidriven.mymobilesecretary.planning.domain
                                .PlanningItemShape(match.task().getTitle(), dueAt, null,
                                        false, false, false));
        if (!decision.allowed()) {
            return IntentResult.clarificationNeeded(String.join("；", decision.requirements()));
        }
        Task changed = taskService.changeDueDate(match.task().getId(), dueAt);
        return IntentResult.message(IntentResult.Action.TASK_UPDATED,
                "已把「%s」轉成行程提醒；會在指定時點提醒，但不占用行程時段，也不參與撞期。"
                        .formatted(changed.getTitle()));
    }

    private IntentResult convertToTodo(IntentCommand command) {
        TaskMatch match = matchOpenTask(command, "轉換");
        if (match.failure() != null) return match.failure();
        var decision = com.aproject.aidriven.mymobilesecretary.planning.domain
                .PlanningItemTransitionPolicy.assess(
                        com.aproject.aidriven.mymobilesecretary.planning.application
                                .PlanningItemClassifier.classify(match.task()),
                        com.aproject.aidriven.mymobilesecretary.planning.domain
                                .PlanningItemType.TODO,
                        new com.aproject.aidriven.mymobilesecretary.planning.domain
                                .PlanningItemShape(match.task().getTitle(), null, null,
                                        false, false, false));
        if (!decision.allowed()) {
            return IntentResult.clarificationNeeded(String.join("；", decision.requirements()));
        }
        Task changed = taskService.changeDueDate(match.task().getId(), null);
        return IntentResult.message(IntentResult.Action.TASK_UPDATED,
                "已把「%s」轉成待辦事項；原提醒時間與提醒排程已移除。"
                        .formatted(changed.getTitle()));
    }

    private IntentResult handleLifestyleMutation(IntentCommand command) {
        try {
            IntentOptions options = command.safeOptions();
            return switch (command.type()) {
                case UPDATE_TASK -> updateTask(command, options);
                case PAUSE_RECURRING_TASK -> pauseRecurring(command, options);
                case RESUME_RECURRING_TASK -> resumeRecurring(command, options);
                case SKIP_RECURRING_OCCURRENCE -> skipRecurring(command, options);
                default -> throw new IllegalArgumentException(
                        "unsupported task lifestyle mutation " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult updateTask(IntentCommand command, IntentOptions options) {
        Task target = taskTarget(command, options);
        TaskPriority priority = null;
        if (command.priority() != null && !command.priority().isBlank()) {
            try {
                priority = TaskPriority.valueOf(command.priority().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("bad priority");
            }
        }
        Task.Category category = null;
        if (options.category() != null) {
            try {
                category = Task.Category.valueOf(options.category().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                throw new IllegalArgumentException("bad category");
            }
        }
        if (options.newTitle() == null && options.description() == null
                && priority == null && category == null) {
            throw new IllegalArgumentException("missing task changes");
        }
        Task changed = taskService.updateTask(
                target.getId(), options.newTitle(), options.description(), priority, category);
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.TASK_UPDATED,
                "已更新待辦「%s」:分類 %s、優先級 %s。".formatted(
                        changed.getTitle(), changed.getCategory(), changed.getPriority()), changed);
    }

    private IntentResult pauseRecurring(IntentCommand command, IntentOptions options) {
        Task target = taskTarget(command, options);
        Task changed = taskService.pauseRecurrence(target.getId());
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.RECURRENCE_PAUSED,
                "已暫停「%s」的固定提醒;原設定保留,之後可以直接恢復。".formatted(changed.getTitle()),
                changed);
    }

    private IntentResult resumeRecurring(IntentCommand command, IntentOptions options) {
        Task target = taskTarget(command, options);
        Task changed = taskService.resumeRecurrence(target.getId());
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.RECURRENCE_RESUMED,
                "已恢復「%s」的固定提醒,下一次是 %s。".formatted(
                        changed.getTitle(), format(changed.getDueAt())), changed);
    }

    private IntentResult skipRecurring(IntentCommand command, IntentOptions options) {
        if ("SCHEDULE".equalsIgnoreCase(options.referenceKind())) {
            ScheduleItem target = scheduleTarget(command, options);
            var skipped = scheduleService.skipRecurringOccurrence(target.getId());
            int canceledReminders = cancelSkippedScheduleReminders(skipped.skipped());
            ScheduleItem remembered = skipped.next() == null ? skipped.skipped() : skipped.next();
            contextService.rememberSchedule(remembered);
            String next = skipped.next() == null
                    ? "固定系列已到截止日,沒有下一場。"
                    : "下一次是 %s%s。".formatted(format(skipped.next().getStartAt()),
                            skipped.next().getStatus() == ScheduleStatus.CONFIRMED
                                    ? "" : ",目前尚待確認");
            String reminder = canceledReminders == 0 ? ""
                    : "\n已取消這一場的 %d 個相關提醒。".formatted(canceledReminders);
            return IntentResult.message(IntentResult.Action.SCHEDULE_RECURRENCE_SKIPPED,
                    "已略過「%s」這一次。\n%s%s".formatted(
                            target.getTitle(), next, reminder));
        }
        Task target = taskTarget(command, options);
        Task changed = taskService.skipRecurringOccurrence(target.getId());
        contextService.rememberTask(changed);
        return IntentResult.taskMessage(IntentResult.Action.RECURRENCE_SKIPPED,
                "已略過「%s」這一次,下一次是 %s。".formatted(
                        changed.getTitle(), format(changed.getDueAt())), changed);
    }

    private int cancelSkippedScheduleReminders(ScheduleItem skipped) {
        String reminderTitle = "提醒:" + skipped.getTitle();
        List<Task> reminders = taskService.listOpenTasks().stream()
                .filter(task -> task.getTitle().equalsIgnoreCase(reminderTitle))
                .filter(task -> task.getDueAt() != null
                        && !task.getDueAt().isAfter(skipped.getStartAt()))
                .toList();
        reminders.forEach(task -> taskService.cancelTask(task.getId()));
        return reminders.size();
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

    private ScheduleItem scheduleTarget(IntentCommand command, IntentOptions options) {
        String title = command.title();
        if ((title == null || title.isBlank()) && options.referenceTitle() != null) {
            title = options.referenceTitle();
        }
        if (title != null && !title.isBlank()) {
            List<ScheduleItem> matches = scheduleService.findReschedulableSchedulesMatching(title);
            if (matches.size() != 1) {
                throw new IllegalArgumentException("schedule target is not unique");
            }
            return matches.getFirst();
        }
        Long id = contextService.scheduleIdAt(options.ordinal());
        if (id == null) {
            throw new IllegalArgumentException("schedule context missing");
        }
        return scheduleService.getSchedule(id);
    }

    private TaskMatch matchOpenTask(IntentCommand command, String actionVerb) {
        if (command.safeOptions().ordinal() != null) {
            Long id = contextService.taskIdAt(command.safeOptions().ordinal());
            if (id != null) {
                return openContextTask(id, actionVerb);
            }
        }
        if (command.title() != null && !command.title().isBlank()) {
            return matchOpenTask(command.title());
        }
        Long id = contextService.taskIdAt(command.safeOptions().ordinal());
        if (id == null) {
            return new TaskMatch(null, IntentResult.clarificationNeeded(
                    "目前沒有可指代的待辦,請說名稱或先列出待辦。"));
        }
        return openContextTask(id, actionVerb);
    }

    private TaskMatch matchOpenTask(String keyword) {
        List<Task> matches = taskService.findOpenTasksMatching(keyword);
        if (matches.isEmpty()) {
            return new TaskMatch(null, IntentResult.clarificationNeeded(
                    "找不到跟「%s」有關的未完成任務。".formatted(keyword)));
        }
        if (matches.size() > 1) {
            contextService.rememberTaskList(matches);
            String titles = java.util.stream.IntStream.range(0, Math.min(matches.size(), 5))
                    .mapToObj(i -> {
                        Task task = matches.get(i);
                        String due = task.getDueAt() == null ? "無期限"
                                : ZonedDateTime.ofInstant(task.getDueAt(), TAIPEI).format(DATE_TIME);
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

    private TaskMatch openContextTask(Long id, String actionVerb) {
        Task task = taskService.getTask(id);
        if (!taskService.listOpenTasks().stream().map(Task::getId).toList().contains(id)) {
            return new TaskMatch(null, IntentResult.clarificationNeeded(
                    "「%s」已經結案,不能再%s。".formatted(task.getTitle(), actionVerb)));
        }
        return new TaskMatch(task, null);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception exception) {
            throw new IllegalArgumentException("bad time: " + value);
        }
    }

    private static TaskPriority parsePriority(String value) {
        if (value == null || value.isBlank()) {
            return TaskPriority.NORMAL;
        }
        try {
            return TaskPriority.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return TaskPriority.NORMAL;
        }
    }

    private static Task.Category parseCategory(String value) {
        try {
            return Task.Category.valueOf(value == null ? "OTHER" : value.toUpperCase());
        } catch (Exception exception) {
            return Task.Category.OTHER;
        }
    }

    private static Task.Recurrence parseRecurrence(String value) {
        try {
            return Task.Recurrence.valueOf(value == null ? "NONE" : value.toUpperCase());
        } catch (Exception exception) {
            return Task.Recurrence.NONE;
        }
    }

    private static Task.ConditionType parseCondition(String value) {
        try {
            return Task.ConditionType.valueOf(value == null ? "NONE" : value.toUpperCase());
        } catch (Exception exception) {
            return Task.ConditionType.NONE;
        }
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

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private record TaskMatch(Task task, IntentResult failure) {
    }
}
