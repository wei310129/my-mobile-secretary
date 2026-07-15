package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 待辦的確定性只讀洞察：下一件、分類整理與完成進度。 */
@Service
public class TaskInsightService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final TaskService taskService;
    private final Clock clock;

    public TaskInsightService(TaskService taskService, Clock clock) {
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * 建議順序固定為逾期、今天到期、其他有期限、無期限；同層再看優先級與期限。
     * 不交給 LLM 評分，避免相同資料每次推薦不同。
     */
    public Optional<Task> recommendNext(Task.Category category) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TAIPEI);
        return taskService.listOpenTasks().stream()
                .filter(task -> category == null || task.getCategory() == category)
                .min(Comparator
                        .comparingInt((Task task) -> dueRank(task, now, today))
                        .thenComparingInt(task -> priorityRank(task.getPriority()))
                        .thenComparing(Task::getDueAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::getCreatedAt));
    }

    public Map<Task.Category, List<Task>> groupOpenByCategory() {
        Map<Task.Category, List<Task>> grouped = new LinkedHashMap<>();
        for (Task.Category category : Task.Category.values()) {
            grouped.put(category, new ArrayList<>());
        }
        taskService.listOpenTasks().forEach(task -> grouped.get(task.getCategory()).add(task));
        grouped.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return grouped;
    }

    public Progress progress(Scope scope) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(TAIPEI));
        LocalDate startDate = scope == Scope.TODAY
                ? now.toLocalDate()
                : now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant from = startDate.atStartOfDay(TAIPEI).toInstant();
        Instant until = startDate.plusDays(scope == Scope.TODAY ? 1 : 7)
                .atStartOfDay(TAIPEI).toInstant();
        int completed = (int) taskService.listCompletedTasks().stream()
                .filter(task -> inside(task.getUpdatedAt(), from, until))
                .count();
        int remaining = (int) taskService.listOpenTasks().stream()
                .filter(task -> task.getDueAt() != null && inside(task.getDueAt(), from, until))
                .count();
        int total = completed + remaining;
        int percentage = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);
        return new Progress(completed, remaining, total, percentage, from, until);
    }

    private static boolean inside(Instant value, Instant from, Instant until) {
        return value != null && !value.isBefore(from) && value.isBefore(until);
    }

    private static int dueRank(Task task, Instant now, LocalDate today) {
        if (task.getDueAt() == null) return 3;
        if (task.getDueAt().isBefore(now)) return 0;
        if (LocalDate.ofInstant(task.getDueAt(), TAIPEI).equals(today)) return 1;
        return 2;
    }

    private static int priorityRank(TaskPriority priority) {
        return switch (priority) {
            case HIGH -> 0;
            case NORMAL -> 1;
            case LOW -> 2;
        };
    }

    public enum Scope {
        TODAY,
        THIS_WEEK
    }

    public record Progress(int completed, int remaining, int total, int percentage,
                           Instant from, Instant until) {
    }
}
