package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskInsightService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Executes task listing, detail and aggregate insight queries. */
@Component
@RequiredArgsConstructor
public final class TaskQueryIntentHandler implements IntentHandler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.LIST_TASKS,
            IntentCommand.Type.ASK_TASK_INFO,
            IntentCommand.Type.LIST_COMPLETED_TASKS,
            IntentCommand.Type.SUGGEST_NEXT_TASK,
            IntentCommand.Type.GROUP_TASKS_BY_CATEGORY,
            IntentCommand.Type.ASK_TASK_PROGRESS,
            IntentCommand.Type.GROUP_TASKS_BY_DUE,
            IntentCommand.Type.ASK_TASK_LOAD,
            IntentCommand.Type.ASK_BUSY_TASK_DAY);

    private final TaskService taskService;
    private final TaskInsightService taskInsightService;
    private final ScheduleService scheduleService;
    private final GeofenceRuleService geofenceService;
    private final ConversationContextService contextService;
    private final Clock clock;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        IntentOptions options = command.safeOptions();
        if (command.type() == IntentCommand.Type.LIST_TASKS) {
            return listTasks(options);
        }
        return handleLifestyleQuery(command, options);
    }

    private IntentResult handleLifestyleQuery(IntentCommand command, IntentOptions options) {
        try {
            return switch (command.type()) {
                case ASK_TASK_INFO -> askTaskInfo(command, options);
                case LIST_COMPLETED_TASKS -> listCompleted(options);
                case SUGGEST_NEXT_TASK -> suggestNextTask(options);
                case GROUP_TASKS_BY_CATEGORY -> groupTasksByCategory();
                case ASK_TASK_PROGRESS -> taskProgress(options);
                case GROUP_TASKS_BY_DUE -> groupTasksByDue();
                case ASK_TASK_LOAD -> taskLoad(options);
                case ASK_BUSY_TASK_DAY -> busiestTaskDay();
                default -> throw new IllegalArgumentException(
                        "unsupported task query intent type " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult listTasks(IntentOptions options) {
        List<Task> open = taskService.listOpenTasks();
        List<Task> tasks = filterTasks(open, options);
        contextService.rememberTaskList(tasks);
        return IntentResult.tasksListed(tasks, taskListAdvice(open));
    }

    private IntentResult askTaskInfo(IntentCommand command, IntentOptions options) {
        Task task = taskTarget(command, options);
        contextService.rememberTask(task);
        return IntentResult.message(IntentResult.Action.TASK_INFO,
                "「%s」%s,分類 %s,狀態 %s。".formatted(task.getTitle(),
                        task.getDueAt() == null ? "沒有期限" : "期限 " + format(task.getDueAt()),
                        task.getCategory(), task.getStatus()));
    }

    private IntentResult listCompleted(IntentOptions options) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TAIPEI);
        String filter = options.filter() == null ? "RECENT" : options.filter().toUpperCase();
        List<Task> completed = taskService.listCompletedTasks().stream().filter(task -> {
            LocalDate date = LocalDate.ofInstant(task.getUpdatedAt(), TAIPEI);
            return switch (filter) {
                case "TODAY" -> date.equals(today);
                case "WEEK" -> !date.isBefore(today.minusDays(6));
                default -> true;
            };
        }).limit(20).toList();
        contextService.rememberTaskList(completed);
        String message = completed.isEmpty() ? "指定範圍內還沒有完成紀錄。"
                : "已完成 %d 件:\n%s".formatted(completed.size(), completed.stream()
                        .map(task -> "✓ %s｜%s".formatted(task.getTitle(), format(task.getUpdatedAt())))
                        .collect(java.util.stream.Collectors.joining("\n")));
        return IntentResult.message(IntentResult.Action.COMPLETED_TASKS_LISTED, message);
    }

    private IntentResult suggestNextTask(IntentOptions options) {
        Task.Category category = options.category() == null ? null : parseCategory(options.category());
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
        contextService.rememberTaskList(groups.values().stream().flatMap(List::stream).toList());
        String message = groups.entrySet().stream()
                .map(entry -> "%s（%d）\n%s".formatted(categoryLabel(entry.getKey()),
                        entry.getValue().size(), entry.getValue().stream()
                                .map(task -> "- " + task.getTitle())
                                .collect(java.util.stream.Collectors.joining("\n"))))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return IntentResult.message(IntentResult.Action.TASKS_GROUPED_BY_CATEGORY, message);
    }

    private IntentResult taskProgress(IntentOptions options) {
        boolean week = "WEEK".equalsIgnoreCase(options.filter());
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
        }).collect(java.util.stream.Collectors.joining("\n\n"));
        return IntentResult.message(IntentResult.Action.TASKS_GROUPED_BY_DUE, message);
    }

    private IntentResult taskLoad(IntentOptions options) {
        boolean nextThreeDays = "NEXT_3_DAYS".equalsIgnoreCase(options.filter());
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
                        "未來七天以 %s 的到期待辦最多，共 %d 件。".formatted(
                                load.date(), load.count())))
                .orElseGet(() -> IntentResult.message(IntentResult.Action.BUSIEST_TASK_DAY_INFO,
                        "未來七天沒有設定期限的待辦。"));
    }

    private List<Task> filterTasks(List<Task> source, IntentOptions options) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TAIPEI);
        return source.stream().filter(task -> {
            String filter = options.filter() == null ? "ALL" : options.filter().toUpperCase();
            boolean date = switch (filter) {
                case "TODAY", "WORK_TODAY" -> task.getDueAt() != null
                        && LocalDate.ofInstant(task.getDueAt(), TAIPEI).equals(today);
                case "TOMORROW" -> task.getDueAt() != null
                        && LocalDate.ofInstant(task.getDueAt(), TAIPEI).equals(today.plusDays(1));
                case "UPCOMING_DUE", "HIGH_AND_DUE" -> task.getDueAt() != null
                        && !task.getDueAt().isBefore(now)
                        && task.getDueAt().isBefore(now.plus(Duration.ofDays(3)));
                case "WEEK" -> task.getDueAt() != null && !task.getDueAt().isBefore(now)
                        && !LocalDate.ofInstant(task.getDueAt(), TAIPEI).isAfter(today.plusDays(7));
                case "OVERDUE" -> task.getDueAt() != null && task.getDueAt().isBefore(now);
                case "NO_DUE" -> task.getDueAt() == null;
                case "STALE" -> task.getCreatedAt().isBefore(now.minus(Duration.ofDays(30)));
                case "MONTH" -> task.getDueAt() != null
                        && java.time.YearMonth.from(LocalDate.ofInstant(task.getDueAt(), TAIPEI))
                                .equals(java.time.YearMonth.from(today));
                case "NEXT_MONTH" -> task.getDueAt() != null
                        && java.time.YearMonth.from(LocalDate.ofInstant(task.getDueAt(), TAIPEI))
                                .equals(java.time.YearMonth.from(today).plusMonths(1));
                default -> true;
            };
            Task.Category wanted = parseCategory(options.category());
            boolean category = options.category() == null || wanted == task.getCategory();
            if ("WORK_TODAY".equals(filter)) {
                category = task.getCategory() == Task.Category.WORK;
            }
            boolean priority = !("HIGH_PRIORITY".equals(filter) || "HIGH_AND_DUE".equals(filter))
                    || task.getPriority() == TaskPriority.HIGH;
            boolean recurrence = switch (filter) {
                case "RECURRING" -> task.getRecurrence() != Task.Recurrence.NONE;
                case "PAUSED_RECURRING" -> task.getRecurrence() != Task.Recurrence.NONE
                        && task.isRecurrencePaused();
                default -> true;
            };
            return date && category && priority && recurrence;
        }).toList();
    }

    private String taskListAdvice(List<Task> tasks) {
        StringBuilder advice = new StringBuilder();
        Instant now = Instant.now(clock);
        List<Task> overdue = tasks.stream()
                .filter(task -> task.getDueAt() != null && task.getDueAt().isBefore(now))
                .limit(3).toList();
        if (!overdue.isEmpty()) {
            advice.append("\n\n已逾期:\n%s\n\n建議直接說「%s改到明天下午三點」，或告訴我已完成。"
                    .formatted(overdue.stream().map(task -> "「" + task.getTitle() + "」")
                                    .collect(java.util.stream.Collectors.joining("\n")),
                            overdue.getFirst().getTitle()));
        }
        List<Task> missing = tasks.stream().filter(task -> task.getDueAt() == null).limit(3).toList();
        if (!missing.isEmpty()) {
            advice.append("\n\n未設定期限:\n%s\n\n建議補一句（例如「%s這週六早上處理」），我才能按時提醒。"
                    .formatted(missing.stream().map(task -> "「" + task.getTitle() + "」")
                                    .collect(java.util.stream.Collectors.joining("\n")),
                            missing.getFirst().getTitle()));
        }
        List<Task> missingPlace = tasks.stream()
                .filter(task -> looksLocationBased(task.getTitle()))
                .filter(task -> geofenceService.listRulesForTask(task.getId()).isEmpty())
                .limit(3).toList();
        if (!missingPlace.isEmpty()) {
            advice.append("\n\n可能需要地點但尚未設定:\n%s\n\n建議可以說「%s是在蝦皮店到店領」。"
                    .formatted(missingPlace.stream().map(task -> "「" + task.getTitle() + "」")
                                    .collect(java.util.stream.Collectors.joining("\n")),
                            missingPlace.getFirst().getTitle()));
        }
        tasks.stream()
                .filter(task -> task.getDueAt() != null && task.getDueAt().isAfter(now))
                .findFirst()
                .flatMap(task -> suggestSlotBefore(task, now))
                .ifPresent(advice::append);
        return advice.toString();
    }

    private Optional<String> suggestSlotBefore(Task task, Instant now) {
        Instant start = task.getDueAt().minus(Duration.ofHours(2));
        if (start.isBefore(now)) {
            start = now.plus(Duration.ofMinutes(15));
        }
        Instant end = start.plus(Duration.ofMinutes(30));
        if (end.isAfter(task.getDueAt())) {
            return Optional.empty();
        }
        Instant selectedStart = start;
        boolean overlaps = scheduleService.listSchedules(ScheduleStatus.CONFIRMED).stream()
                .anyMatch(item -> item.getStartAt().isBefore(end)
                        && selectedStart.isBefore(item.getEndAt()));
        if (overlaps) {
            return Optional.empty();
        }
        return Optional.of("\n\n建議:把「%s」排在 %s-%s(期限前),OK 的話跟我說一聲我就排進行程。"
                .formatted(task.getTitle(),
                        ZonedDateTime.ofInstant(selectedStart, TAIPEI).format(DATE_TIME),
                        ZonedDateTime.ofInstant(end, TAIPEI)
                                .format(DateTimeFormatter.ofPattern("HH:mm"))));
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

    public static boolean looksLocationBased(String title) {
        if (title == null) {
            return false;
        }
        String value = title.toLowerCase(java.util.Locale.ROOT);
        return List.of("拿", "領", "取", "買", "送", "寄", "繳", "pickup", "buy", "deliver")
                .stream().anyMatch(value::contains);
    }

    private static Task.Category parseCategory(String value) {
        try {
            return Task.Category.valueOf(value == null ? "OTHER" : value.toUpperCase());
        } catch (Exception exception) {
            return Task.Category.OTHER;
        }
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

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }
}
