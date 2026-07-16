package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 確定性回答「何時提醒」問題，避免被誤分類成行程資訊或改期。 */
@Service
public class ReminderTimingAnswerService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final TaskService taskService;
    private final Clock clock;

    public ReminderTimingAnswerService(TaskService taskService, Clock clock) {
        this.taskService = taskService;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text) {
        if (!isTimingQuestion(text)) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        List<Task> matches = taskService.listTasks().stream()
                .filter(task -> matchesTitle(normalized, task.getTitle()))
                .sorted(Comparator
                        .comparing((Task task) -> isOpen(task.getStatus())).reversed()
                        .thenComparing(task -> displayTitle(task.getTitle()).length(),
                                Comparator.reverseOrder())
                        .thenComparing(Task::getUpdatedAt, Comparator.reverseOrder()))
                .toList();
        if (matches.isEmpty()) {
            return Optional.empty();
        }

        String selectedTitle = displayTitle(matches.getFirst().getTitle());
        boolean selectedIsOpen = isOpen(matches.getFirst().getStatus());
        List<Task> sameTitle = matches.stream()
                .filter(task -> displayTitle(task.getTitle()).equalsIgnoreCase(selectedTitle))
                .filter(task -> isOpen(task.getStatus()) == selectedIsOpen)
                .toList();
        List<Instant> times = sameTitle.stream().map(Task::getDueAt)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList();
        if (times.isEmpty()) {
            return Optional.of(IntentResult.message(IntentResult.Action.SCHEDULE_REMINDER_INFO,
                    "「%s」目前沒有設定提醒時間。\n\n請告訴我日期與時間，我再替你設定。"
                            .formatted(selectedTitle)));
        }

        Instant now = Instant.now(clock);
        StringBuilder message = new StringBuilder("「%s」目前記錄的提醒時間:"
                .formatted(selectedTitle));
        times.forEach(time -> message.append("\n%s｜%s".formatted(format(time),
                time.isBefore(now) ? "已逾期" : "尚未到")));
        if (times.stream().allMatch(time -> time.isBefore(now))) {
            message.append("\n\n提醒時間已逾期；我不會自行改成今天早上或其他時間。")
                    .append("\n\n請告訴我要改到何時，或告訴我這件事已完成。");
        }
        return Optional.of(IntentResult.message(
                IntentResult.Action.SCHEDULE_REMINDER_INFO, message.toString()));
    }

    static boolean isTimingQuestion(String text) {
        String normalized = normalize(text);
        boolean asksWhen = normalized.contains("什麼時候") || normalized.contains("何時")
                || normalized.contains("幾點") || normalized.contains("時機")
                || normalized.contains("提醒時間");
        boolean modifying = normalized.contains("改到") || normalized.contains("改成")
                || normalized.contains("設定") || normalized.contains("新增")
                || normalized.contains("建立");
        return normalized.contains("提醒") && asksWhen && !modifying;
    }

    private static boolean matchesTitle(String normalizedText, String title) {
        String normalizedTitle = normalize(displayTitle(title));
        return !normalizedTitle.isBlank() && normalizedText.contains(normalizedTitle);
    }

    private static String displayTitle(String title) {
        return title != null && title.startsWith("提醒:") ? title.substring("提醒:".length()) : title;
    }

    private static boolean isOpen(TaskStatus status) {
        return status != TaskStatus.CONFIRMED && status != TaskStatus.CANCELED;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\s「」『』:：]", "").toLowerCase();
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(TIME);
    }
}
