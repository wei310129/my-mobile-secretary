package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Composes one deterministic cross-domain secretary view from already authorized domain data. */
@Service
public final class SecretaryOverviewService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private final Clock clock;

    public SecretaryOverviewService(Clock clock) {
        this.clock = clock;
    }

    public IntentResult agenda(List<Task> tasks, List<ScheduleItem> schedules, String filter) {
        return compose(IntentResult.Action.AGENDA_LISTED, tasks, schedules, filter, false);
    }

    public IntentResult summary(List<Task> tasks, List<ScheduleItem> schedules, String filter) {
        return compose(IntentResult.Action.AGENDA_SUMMARY, tasks, schedules, filter, true);
    }

    private IntentResult compose(IntentResult.Action action, List<Task> tasks,
                                 List<ScheduleItem> schedules, String filter,
                                 boolean includeLoadSummary) {
        Instant now = Instant.now(clock);
        List<Task> orderedTasks = tasks.stream()
                .sorted(Comparator.comparing(Task::getDueAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<ScheduleItem> orderedSchedules = schedules.stream()
                .sorted(Comparator.comparing(ScheduleItem::getStartAt))
                .toList();
        String scope = scopeLabel(filter);

        StringBuilder reply = new StringBuilder();
        if (includeLoadSummary) {
            long scheduledMinutes = orderedSchedules.stream()
                    .mapToLong(item -> Duration.between(item.getStartAt(), item.getEndAt()).toMinutes())
                    .sum();
            long dueTasks = orderedTasks.stream().filter(task -> task.getDueAt() != null).count();
            reply.append("%s共有 %d 個行程（約 %d 小時 %d 分鐘）與 %d 件待辦，其中 %d 件有期限。"
                    .formatted(scope, orderedSchedules.size(), scheduledMinutes / 60,
                            scheduledMinutes % 60, orderedTasks.size(), dueTasks));
        } else {
            reply.append("%s共有 %d 個行程與 %d 件待辦。"
                    .formatted(scope, orderedSchedules.size(), orderedTasks.size()));
        }

        List<String> current = new ArrayList<>();
        orderedSchedules.stream()
                .filter(item -> !item.getStartAt().isAfter(now) && item.getEndAt().isAfter(now))
                .limit(2)
                .map(item -> "行程「%s」進行到 %s".formatted(
                        item.getTitle(), format(item.getEndAt())))
                .forEach(current::add);
        orderedTasks.stream()
                .filter(task -> task.getDueAt() != null && task.getDueAt().isBefore(now))
                .limit(2)
                .map(task -> "待辦「%s」已逾期".formatted(task.getTitle()))
                .forEach(current::add);
        reply.append("\n\n現在｜")
                .append(current.isEmpty() ? "沒有進行中的行程或已逾期待辦。"
                        : String.join("；", current) + "。");

        List<UpcomingItem> upcoming = new ArrayList<>();
        orderedSchedules.stream()
                .filter(item -> item.getStartAt().isAfter(now))
                .map(item -> new UpcomingItem(item.getStartAt(),
                        "行程「%s」｜%s".formatted(item.getTitle(), format(item.getStartAt()))))
                .forEach(upcoming::add);
        orderedTasks.stream()
                .filter(task -> task.getDueAt() == null || task.getDueAt().isAfter(now))
                .map(task -> new UpcomingItem(task.getDueAt(),
                        task.getDueAt() == null
                                ? "待辦「%s」｜未設期限".formatted(task.getTitle())
                                : "待辦「%s」｜%s".formatted(
                                        task.getTitle(), format(task.getDueAt()))))
                .forEach(upcoming::add);
        upcoming.sort(Comparator.comparing(UpcomingItem::at,
                Comparator.nullsLast(Comparator.naturalOrder())));
        reply.append("\n\n接下來｜");
        if (upcoming.isEmpty()) {
            reply.append("目前沒有已確認項目。");
        } else {
            reply.append(upcoming.stream().limit(4)
                    .map(UpcomingItem::text)
                    .collect(java.util.stream.Collectors.joining("\n- ", "\n- ", "")));
        }

        List<String> alerts = alerts(orderedTasks, orderedSchedules, now);
        if (!alerts.isEmpty()) {
            reply.append("\n\n注意｜").append(String.join("；", alerts)).append("。");
        }
        return IntentResult.message(action, reply.toString());
    }

    private static List<String> alerts(List<Task> tasks, List<ScheduleItem> schedules, Instant now) {
        List<String> alerts = new ArrayList<>();
        long overdue = tasks.stream()
                .filter(task -> task.getDueAt() != null && task.getDueAt().isBefore(now))
                .count();
        if (overdue > 0) {
            alerts.add("有 %d 件待辦已逾期".formatted(overdue));
        } else {
            tasks.stream()
                    .filter(task -> task.getPriority() == TaskPriority.HIGH
                            && task.getDueAt() != null && !task.getDueAt().isBefore(now))
                    .findFirst()
                    .ifPresent(task -> alerts.add("高優先待辦「%s」將於 %s 到期"
                            .formatted(task.getTitle(), format(task.getDueAt()))));
        }
        for (int index = 1; index < schedules.size(); index++) {
            ScheduleItem previous = schedules.get(index - 1);
            ScheduleItem current = schedules.get(index);
            if (current.getStartAt().isBefore(previous.getEndAt())) {
                alerts.add("行程「%s」與「%s」時間重疊"
                        .formatted(previous.getTitle(), current.getTitle()));
                break;
            }
        }
        return alerts;
    }

    private static String scopeLabel(String filter) {
        if (filter == null) return "接下來";
        return switch (filter.toUpperCase(java.util.Locale.ROOT)) {
            case "TODAY", "WORK_TODAY" -> "今天";
            case "TOMORROW", "TOMORROW_FIRST" -> "明天";
            case "WEEK" -> "這週";
            default -> "接下來";
        };
    }

    private static String format(Instant value) {
        return DATE_TIME.format(value.atZone(TAIPEI));
    }

    private record UpcomingItem(Instant at, String text) {
    }
}
