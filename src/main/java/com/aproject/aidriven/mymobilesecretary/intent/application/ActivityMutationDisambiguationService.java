package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Prevents a mutation from guessing between an identically named task and schedule. */
@Service
public class ActivityMutationDisambiguationService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final TaskService taskService;
    private final ScheduleService scheduleService;

    public ActivityMutationDisambiguationService(TaskService taskService,
                                                 ScheduleService scheduleService) {
        this.taskService = taskService;
        this.scheduleService = scheduleService;
    }

    public Optional<IntentResult> answer(String text) {
        String normalized = normalize(text);
        if (!isMutation(normalized) || explicitlySelectsKind(normalized)) {
            return Optional.empty();
        }

        List<Task> tasks = taskService.listOpenTasks().stream()
                .filter(task -> mentions(normalized, task.getTitle()))
                .toList();
        List<ScheduleItem> schedules = scheduleService.listSchedules(null).stream()
                .filter(ActivityMutationDisambiguationService::canMutate)
                .filter(item -> mentions(normalized, item.getTitle()))
                .toList();

        return tasks.stream()
                .flatMap(task -> schedules.stream()
                        .filter(item -> normalize(task.getTitle()).equals(normalize(item.getTitle())))
                        .map(item -> new AmbiguousActivity(task, item)))
                .findFirst()
                .map(this::clarification);
    }

    private IntentResult clarification(AmbiguousActivity match) {
        Task task = match.task();
        ScheduleItem schedule = match.schedule();
        String taskTime = task.getDueAt() == null ? "未設定期限" : format(task.getDueAt());
        String scheduleTime = "%s–%s".formatted(
                format(schedule.getStartAt()), time(schedule.getEndAt()));
        return IntentResult.clarificationNeeded(
                "找到同名的待辦與行程，我還不能確定你要修改哪一個，因此尚未異動：\n"
                        + "1. 待辦「%s」｜%s\n".formatted(task.getTitle(), taskTime)
                        + "2. 行程「%s」｜%s\n\n".formatted(schedule.getTitle(), scheduleTime)
                        + "請明確回覆「修改待辦 %s」或「修改行程 %s」。"
                                .formatted(task.getTitle(), schedule.getTitle()));
    }

    private static boolean isMutation(String text) {
        return containsAny(text, "延後", "延期", "提前", "改到", "改成", "改時間",
                "取消", "刪掉", "不要了");
    }

    private static boolean explicitlySelectsKind(String text) {
        return containsAny(text, "這個待辦", "那個待辦", "修改待辦", "待辦事項", "任務")
                || containsAny(text, "這個行程", "那個行程", "修改行程", "排程");
    }

    private static boolean canMutate(ScheduleItem item) {
        return item.getStatus() == ScheduleStatus.PROPOSED
                || item.getStatus() == ScheduleStatus.CONFIRMED
                || item.getStatus() == ScheduleStatus.PENDING;
    }

    private static boolean mentions(String normalizedText, String title) {
        String normalizedTitle = normalize(title);
        return !normalizedTitle.isBlank() && normalizedText.contains(normalizedTitle);
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? ""
                : value.replaceAll("[\\s「」『』:：，,。！？?]", "").toLowerCase();
    }

    private static String format(java.time.Instant value) {
        return ZonedDateTime.ofInstant(value, TAIPEI).format(DATE_TIME);
    }

    private static String time(java.time.Instant value) {
        return ZonedDateTime.ofInstant(value, TAIPEI)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private record AmbiguousActivity(Task task, ScheduleItem schedule) {
    }
}
