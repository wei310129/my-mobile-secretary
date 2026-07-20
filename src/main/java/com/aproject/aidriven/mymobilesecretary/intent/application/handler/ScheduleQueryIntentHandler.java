package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.DailyScheduleOverviewService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleInsightService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
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

/** Executes schedule detail, agenda, availability and aggregate insight queries. */
@Component
@RequiredArgsConstructor
public final class ScheduleQueryIntentHandler implements IntentHandler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final Set<IntentCommand.Type> SUPPORTED_TYPES = Set.of(
            IntentCommand.Type.LIST_SCHEDULES,
            IntentCommand.Type.ASK_SCHEDULE_INFO,
            IntentCommand.Type.LIST_SCHEDULES_ON_DATE,
            IntentCommand.Type.LIST_AGENDA,
            IntentCommand.Type.ASK_AVAILABILITY,
            IntentCommand.Type.AGENDA_SUMMARY,
            IntentCommand.Type.ASK_NEXT_SCHEDULE,
            IntentCommand.Type.ASK_SCHEDULE_GAP,
            IntentCommand.Type.GROUP_SCHEDULES_BY_DAY,
            IntentCommand.Type.CHECK_SCHEDULE_CONFLICTS,
            IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY,
            IntentCommand.Type.ASK_LONGEST_SCHEDULE,
            IntentCommand.Type.GROUP_SCHEDULES_BY_PLACE);

    private final ScheduleService scheduleService;
    private final TaskService taskService;
    private final ConversationContextService contextService;
    private final FreeSlotService freeSlotService;
    private final ScheduleInsightService scheduleInsightService;
    private final PlaceService placeService;
    private final DailyScheduleOverviewService dailyScheduleOverviewService;
    private final Clock clock;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        IntentOptions options = command.safeOptions();
        return switch (command.type()) {
            case LIST_SCHEDULES -> listSchedules(options);
            case ASK_SCHEDULE_INFO -> askScheduleInfo(command);
            case LIST_SCHEDULES_ON_DATE -> listSchedulesOnDate(command);
            case LIST_AGENDA, ASK_AVAILABILITY, AGENDA_SUMMARY,
                    ASK_NEXT_SCHEDULE, ASK_SCHEDULE_GAP,
                    GROUP_SCHEDULES_BY_DAY, CHECK_SCHEDULE_CONFLICTS,
                    ASK_BUSY_SCHEDULE_DAY, ASK_LONGEST_SCHEDULE,
                    GROUP_SCHEDULES_BY_PLACE -> handleLifestyleQuery(command, options);
            default -> throw new IllegalArgumentException(
                    "unsupported schedule query intent type " + command.type());
        };
    }

    private IntentResult handleLifestyleQuery(IntentCommand command, IntentOptions options) {
        try {
            return switch (command.type()) {
                case LIST_AGENDA -> listAgenda(options);
                case ASK_AVAILABILITY -> askAvailability(command);
                case AGENDA_SUMMARY -> agendaSummary(options);
                case ASK_NEXT_SCHEDULE -> nextSchedule();
                case ASK_SCHEDULE_GAP -> scheduleGap(command, options);
                case GROUP_SCHEDULES_BY_DAY -> groupSchedules(options);
                case CHECK_SCHEDULE_CONFLICTS -> checkScheduleConflicts(command, options);
                case ASK_BUSY_SCHEDULE_DAY -> busiestScheduleDay(command, options);
                case ASK_LONGEST_SCHEDULE -> longestSchedule(options);
                case GROUP_SCHEDULES_BY_PLACE -> groupSchedulesByPlace(options);
                default -> throw new IllegalArgumentException(
                        "unsupported lifestyle schedule query type " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult listSchedules(IntentOptions options) {
        List<ScheduleItem> items = filterSchedules(upcomingSchedules(), options);
        contextService.rememberScheduleList(items);
        return IntentResult.schedulesListed(items);
    }

    private IntentResult askScheduleInfo(IntentCommand command) {
        require(command.title(), "title");
        ScheduleMatch match = uniqueScheduleMatch(
                scheduleService.findReschedulableSchedulesMatching(command.title()),
                command.title(), "查");
        if (match.failure() != null) {
            return match.failure();
        }
        return IntentResult.scheduleInfo(match.item(), match.item().getPlaceId() == null
                ? null : placeService.getPlace(match.item().getPlaceId()));
    }

    private IntentResult listSchedulesOnDate(IntentCommand command) {
        Instant value = parse(command.startAt());
        if (value == null) {
            throw new IllegalArgumentException("daily schedule query missing startAt");
        }
        return dailyScheduleOverviewService.overview(LocalDate.ofInstant(value, TAIPEI));
    }

    private IntentResult listAgenda(IntentOptions options) {
        List<Task> tasks = filterTasks(taskService.listOpenTasks(), options);
        List<ScheduleItem> schedules = filterSchedules(upcomingSchedules(), options);
        contextService.rememberTaskList(tasks);
        contextService.rememberScheduleList(schedules);
        if (tasks.isEmpty() && schedules.isEmpty()) {
            return IntentResult.message(IntentResult.Action.AGENDA_LISTED,
                    "指定範圍內沒有待辦或行程。 ");
        }
        String taskLines = tasks.stream().limit(10)
                .map(task -> "待辦｜%s%s".formatted(task.getTitle(),
                        task.getDueAt() == null ? "" : "｜" + format(task.getDueAt())))
                .collect(java.util.stream.Collectors.joining("\n"));
        String scheduleLines = schedules.stream().limit(10)
                .map(item -> "行程｜%s｜%s".formatted(item.getTitle(), format(item.getStartAt())))
                .collect(java.util.stream.Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.AGENDA_LISTED,
                "行程與待辦:\n" + java.util.stream.Stream.of(scheduleLines, taskLines)
                        .filter(value -> !value.isBlank())
                        .collect(java.util.stream.Collectors.joining("\n")));
    }

    private IntentResult askAvailability(IntentCommand command) {
        Instant from = requiredTime(command.startAt(), "startAt");
        Instant until = requiredTime(command.endAt(), "endAt");
        boolean available = freeSlotService.available(from, until);
        return IntentResult.message(IntentResult.Action.AVAILABILITY_CHECKED,
                available ? "%s 到 %s 有空。".formatted(format(from), format(until))
                        : "%s 到 %s 已有行程。".formatted(format(from), format(until)));
    }

    private IntentResult agendaSummary(IntentOptions options) {
        List<Task> tasks = filterTasks(taskService.listOpenTasks(), options);
        List<ScheduleItem> schedules = filterSchedules(upcomingSchedules(), options);
        long scheduledMinutes = schedules.stream()
                .mapToLong(item -> Duration.between(
                        item.getStartAt(), item.getEndAt()).toMinutes()).sum();
        long dueTasks = tasks.stream().filter(task -> task.getDueAt() != null).count();
        return IntentResult.message(IntentResult.Action.AGENDA_SUMMARY,
                "共有 %d 個行程(約 %d 小時 %d 分鐘)、%d 件待辦,其中 %d 件有期限。".formatted(
                        schedules.size(), scheduledMinutes / 60, scheduledMinutes % 60,
                        tasks.size(), dueTasks));
    }

    private IntentResult nextSchedule() {
        Optional<ScheduleItem> found = scheduleInsightService.next();
        if (found.isEmpty()) {
            return IntentResult.message(IntentResult.Action.NEXT_SCHEDULE_INFO,
                    "接下來沒有已確認行程。 ");
        }
        ScheduleItem item = found.get();
        contextService.rememberSchedule(item);
        Duration until = Duration.between(Instant.now(clock), item.getStartAt());
        String timing = until.isNegative() || until.isZero()
                ? "正在進行中" : "還有 %d 小時 %d 分鐘".formatted(
                        until.toHours(), until.toMinutesPart());
        String place = item.getPlaceId() == null ? ""
                : "｜" + placeService.getPlace(item.getPlaceId()).getName();
        return IntentResult.message(IntentResult.Action.NEXT_SCHEDULE_INFO,
                "下一個是「%s」｜%s%s，%s。".formatted(
                        item.getTitle(), format(item.getStartAt()), place, timing));
    }

    private IntentResult scheduleGap(IntentCommand command, IntentOptions options) {
        ScheduleItem first;
        ScheduleItem second;
        if (command.title() != null && options.referenceTitle() != null) {
            first = uniqueSchedule(command.title());
            second = uniqueSchedule(options.referenceTitle());
        } else {
            List<ScheduleItem> upcoming = scheduleInsightService.upcoming();
            if (upcoming.size() < 2) {
                return IntentResult.clarificationNeeded(
                        "至少要有兩個接下來的行程才能算間隔。 ");
            }
            first = upcoming.get(0);
            second = upcoming.get(1);
        }
        var gap = scheduleInsightService.gap(first, second);
        String message = gap.overlapping()
                ? "「%s」和「%s」重疊 %d 分鐘。".formatted(
                        gap.first().getTitle(), gap.second().getTitle(),
                        Math.abs(gap.duration().toMinutes()))
                : "「%s」結束到「%s」開始有 %d 分鐘。".formatted(
                        gap.first().getTitle(), gap.second().getTitle(), gap.duration().toMinutes());
        return IntentResult.message(IntentResult.Action.SCHEDULE_GAP_INFO, message);
    }

    private IntentResult groupSchedules(IntentOptions options) {
        var groups = scheduleInsightService.groupByDay(
                filterSchedules(scheduleInsightService.upcoming(), options));
        if (groups.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_DAY,
                    "指定範圍內沒有已確認行程。 ");
        }
        String message = groups.entrySet().stream().map(entry -> "%s\n%s".formatted(entry.getKey(),
                        entry.getValue().stream().map(item -> "- %s｜%s".formatted(item.getTitle(),
                                        ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI)
                                                .format(DateTimeFormatter.ofPattern("HH:mm"))))
                                .collect(java.util.stream.Collectors.joining("\n"))))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_DAY, message);
    }

    private IntentResult checkScheduleConflicts(IntentCommand command, IntentOptions options) {
        List<ScheduleInsightService.Gap> conflicts;
        ScheduleItem target = null;
        if (command.title() != null && !command.title().isBlank()) {
            target = uniqueSchedule(command.title());
            ScheduleItem selected = target;
            conflicts = scheduleInsightService.upcoming().stream()
                    .filter(other -> !java.util.Objects.equals(other.getId(), selected.getId()))
                    .map(other -> scheduleInsightService.gap(selected, other))
                    .filter(ScheduleInsightService.Gap::overlapping)
                    .toList();
        } else {
            conflicts = scheduleInsightService.conflicts(
                    filterSchedules(scheduleInsightService.upcoming(), options));
        }
        String message = conflicts.isEmpty()
                ? target == null ? "指定範圍內沒有時間重疊的已確認行程。"
                        : "「%s」沒有和其他已確認行程重疊。".formatted(target.getTitle())
                : conflicts.stream().map(this::describeConflict)
                        .collect(java.util.stream.Collectors.joining("\n\n"))
                        + "\n\n下一步建議：告訴我要改哪個行程，或直接指定新時間。"
                        + "\n我不會自行修改或確認。";
        return IntentResult.message(IntentResult.Action.SCHEDULE_CONFLICTS_CHECKED, message);
    }

    private String describeConflict(ScheduleInsightService.Gap gap) {
        ScheduleItem first = gap.first();
        ScheduleItem second = gap.second();
        Instant overlapStart = first.getStartAt().isAfter(second.getStartAt())
                ? first.getStartAt() : second.getStartAt();
        Instant overlapEnd = first.getEndAt().isBefore(second.getEndAt())
                ? first.getEndAt() : second.getEndAt();
        Duration duration = Duration.between(overlapStart, overlapEnd);
        Duration firstDuration = Duration.between(first.getStartAt(), first.getEndAt());
        Duration secondDuration = Duration.between(second.getStartAt(), second.getEndAt());
        Instant firstEarlierStart = second.getStartAt().minus(firstDuration);
        Instant secondLaterEnd = first.getEndAt().plus(secondDuration);
        return "「%s」%s–%s 與「%s」%s–%s；重疊 %s–%s（%d 分鐘）。"
                .formatted(first.getTitle(), format(first.getStartAt()), time(first.getEndAt()),
                        second.getTitle(), format(second.getStartAt()), time(second.getEndAt()),
                        format(overlapStart), time(overlapEnd), duration.toMinutes())
                + "\n\n可考慮以下時段：\nA.「%s」改到 %s–%s\nB.「%s」改到 %s–%s"
                        .formatted(first.getTitle(), format(firstEarlierStart), time(second.getStartAt()),
                                second.getTitle(), format(first.getEndAt()), time(secondLaterEnd))
                + "\n\n選定後我會再檢查其他衝突。";
    }

    private IntentResult busiestScheduleDay(IntentCommand command, IntentOptions options) {
        List<ScheduleItem> items = filterSchedulesByRange(
                filterSchedules(scheduleInsightService.upcoming(), options),
                command.startAt(), command.endAt());
        return scheduleInsightService.busiestDay(items)
                .map(load -> IntentResult.message(IntentResult.Action.BUSIEST_SCHEDULE_DAY_INFO,
                        "%s 的已排行程最滿，共 %d 個、約 %d 小時 %d 分鐘。".formatted(
                                load.date(), load.count(), load.minutes() / 60, load.minutes() % 60)))
                .orElseGet(() -> IntentResult.message(IntentResult.Action.BUSIEST_SCHEDULE_DAY_INFO,
                        "指定範圍內沒有已確認行程。"));
    }

    private IntentResult longestSchedule(IntentOptions options) {
        return scheduleInsightService.longest(
                        filterSchedules(scheduleInsightService.upcoming(), options))
                .map(item -> {
                    contextService.rememberSchedule(item);
                    long minutes = Duration.between(item.getStartAt(), item.getEndAt()).toMinutes();
                    return IntentResult.message(IntentResult.Action.LONGEST_SCHEDULE_INFO,
                            "最長的是「%s」，%s 開始，共 %d 小時 %d 分鐘。".formatted(
                                    item.getTitle(), format(item.getStartAt()),
                                    minutes / 60, minutes % 60));
                }).orElseGet(() -> IntentResult.message(IntentResult.Action.LONGEST_SCHEDULE_INFO,
                        "指定範圍內沒有已確認行程。"));
    }

    private IntentResult groupSchedulesByPlace(IntentOptions options) {
        List<ScheduleItem> items = filterSchedules(scheduleInsightService.upcoming(), options);
        if (items.isEmpty()) {
            return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_PLACE,
                    "指定範圍內沒有已確認行程。 ");
        }
        contextService.rememberScheduleList(items);
        java.util.Map<String, List<ScheduleItem>> groups = new java.util.LinkedHashMap<>();
        items.forEach(item -> {
            String place = item.getPlaceId() == null ? "未設定地點"
                    : placeService.getPlace(item.getPlaceId()).getName();
            groups.computeIfAbsent(place, ignored -> new java.util.ArrayList<>()).add(item);
        });
        String message = groups.entrySet().stream().map(entry -> {
            String lines = entry.getValue().stream().limit(5)
                    .map(item -> "- %s｜%s".formatted(item.getTitle(), format(item.getStartAt())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            String tail = entry.getValue().size() > 5
                    ? "\n…等 %d 個".formatted(entry.getValue().size()) : "";
            return "%s（%d）\n%s%s".formatted(
                    entry.getKey(), entry.getValue().size(), lines, tail);
        }).collect(java.util.stream.Collectors.joining("\n\n"));
        return IntentResult.message(IntentResult.Action.SCHEDULES_GROUPED_BY_PLACE, message);
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

    private List<ScheduleItem> filterSchedules(
            List<ScheduleItem> source, IntentOptions options) {
        LocalDate today = LocalDate.now(clock.withZone(TAIPEI));
        String filter = options.filter() == null ? "UPCOMING" : options.filter().toUpperCase();
        List<ScheduleItem> filtered = source.stream().filter(item -> switch (filter) {
            case "TODAY", "WORK_TODAY" -> LocalDate.ofInstant(
                    item.getStartAt(), TAIPEI).equals(today);
            case "TOMORROW", "TOMORROW_FIRST" -> LocalDate.ofInstant(
                    item.getStartAt(), TAIPEI).equals(today.plusDays(1));
            case "WEEK" -> !LocalDate.ofInstant(item.getStartAt(), TAIPEI)
                    .isAfter(today.plusDays(7));
            case "WEEKEND" -> {
                LocalDate date = LocalDate.ofInstant(item.getStartAt(), TAIPEI);
                yield !date.isAfter(today.plusDays(7))
                        && (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                        || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
            }
            case "WEEKDAY" -> {
                LocalDate date = LocalDate.ofInstant(item.getStartAt(), TAIPEI);
                yield !date.isAfter(today.plusDays(7))
                        && date.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                        && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY;
            }
            case "MORNING" -> ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).getHour() < 12;
            case "AFTERNOON" -> {
                int hour = ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).getHour();
                yield hour >= 12 && hour < 18;
            }
            case "EVENING" -> ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).getHour() >= 18;
            case "WITH_PLACE" -> item.getPlaceId() != null;
            case "NO_PLACE" -> item.getPlaceId() == null;
            case "RECURRING" -> item.getRecurrence() != ScheduleItem.Recurrence.NONE;
            case "ONE_TIME" -> item.getRecurrence() == ScheduleItem.Recurrence.NONE;
            case "LONG" -> Duration.between(item.getStartAt(), item.getEndAt()).toMinutes() > 120;
            default -> true;
        }).toList();
        return "TOMORROW_FIRST".equals(filter) ? filtered.stream().limit(1).toList() : filtered;
    }

    private List<ScheduleItem> upcomingSchedules() {
        Instant now = Instant.now(clock);
        return scheduleService.listSchedules(ScheduleStatus.CONFIRMED).stream()
                .filter(item -> item.getEndAt().isAfter(now)).toList();
    }

    private ScheduleItem uniqueSchedule(String title) {
        List<ScheduleItem> matches = scheduleService.findReschedulableSchedulesMatching(title);
        if (matches.size() != 1) {
            throw new IllegalArgumentException("schedule target is not unique");
        }
        return matches.getFirst();
    }

    private ScheduleMatch uniqueScheduleMatch(
            List<ScheduleItem> matches, String keyword, String actionVerb) {
        if (matches.isEmpty()) {
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "找不到跟「%s」有關、還能%s的行程。".formatted(keyword, actionVerb)));
        }
        if (matches.size() > 1) {
            String titles = matches.stream().limit(5)
                    .map(item -> "「%s」(%s)".formatted(item.getTitle(), format(item.getStartAt())))
                    .collect(java.util.stream.Collectors.joining("\n"));
            return new ScheduleMatch(null, IntentResult.clarificationNeeded(
                    "有 %d 個行程都符合:\n%s\n\n請告訴我日期或時間，我才不會%s錯。"
                            .formatted(matches.size(), titles, actionVerb)));
        }
        return new ScheduleMatch(matches.getFirst(), null);
    }

    private static List<ScheduleItem> filterSchedulesByRange(
            List<ScheduleItem> source, String startAt, String endAt) {
        Instant start = parse(startAt);
        Instant end = parse(endAt);
        if (start == null && end == null) {
            return source;
        }
        return source.stream()
                .filter(item -> start == null || !item.getStartAt().isBefore(start))
                .filter(item -> end == null || item.getStartAt().isBefore(end))
                .toList();
    }

    private static Task.Category parseCategory(String value) {
        try {
            return Task.Category.valueOf(value == null ? "OTHER" : value.toUpperCase());
        } catch (Exception exception) {
            return Task.Category.OTHER;
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

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private static String time(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private record ScheduleMatch(ScheduleItem item, IntentResult failure) {
    }
}
