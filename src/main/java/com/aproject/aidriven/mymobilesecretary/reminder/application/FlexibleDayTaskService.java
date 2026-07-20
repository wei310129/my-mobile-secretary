package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.planner.application.FreeSlotService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.FlexibleDayTaskPlan;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.FlexibleDayTaskPlan.SourceKind;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.FlexibleDayTaskPlanRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Plans a task on a calendar date without creating a fake all-day busy schedule. */
@Service
@Transactional
public class FlexibleDayTaskService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Duration REMINDER_WINDOW = Duration.ofMinutes(15);

    private final FlexibleDayTaskPlanRepository repository;
    private final TaskService taskService;
    private final FreeSlotService freeSlotService;
    private final Clock clock;

    public FlexibleDayTaskService(FlexibleDayTaskPlanRepository repository,
                                  TaskService taskService,
                                  FreeSlotService freeSlotService,
                                  Clock clock) {
        this.repository = repository;
        this.taskService = taskService;
        this.freeSlotService = freeSlotService;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public PlannedTask plan(String title, String description, LocalDate targetDate,
                            LocalDate reminderDate, LocalTime explicitReminderTime,
                            SourceKind sourceKind) {
        if (title == null || title.isBlank() || targetDate == null || reminderDate == null) {
            throw new IllegalArgumentException("title, target date and reminder date are required");
        }
        if (reminderDate.isAfter(targetDate)) {
            throw new IllegalArgumentException("reminder date cannot be after target date");
        }
        Instant now = Instant.now(clock);
        Instant remindAt = explicitReminderTime == null
                ? suggestReminder(reminderDate, now)
                : reminderDate.atTime(explicitReminderTime).atZone(TAIPEI).toInstant();
        if (remindAt.isBefore(now.plus(Duration.ofMinutes(5)))) {
            throw new IllegalArgumentException("提醒時間至少要在目前時間五分鐘後");
        }
        Task task = taskService.createTask(title.strip(), description, TaskPriority.NORMAL,
                remindAt, Task.Category.PERSONAL, Task.Recurrence.NONE,
                Task.ConditionType.NONE, null);
        FlexibleDayTaskPlan plan = repository.save(FlexibleDayTaskPlan.schedule(
                task.getId(), targetDate, remindAt, sourceKind, now));
        return new PlannedTask(task, plan, explicitReminderTime == null);
    }

    private Instant suggestReminder(LocalDate reminderDate, Instant now) {
        Instant dayStart = reminderDate.atTime(LocalTime.of(8, 0)).atZone(TAIPEI).toInstant();
        Instant start = dayStart.isAfter(now.plus(Duration.ofMinutes(5)))
                ? dayStart : now.plus(Duration.ofMinutes(5));
        Instant end = reminderDate.atTime(LocalTime.of(23, 0)).atZone(TAIPEI).toInstant();
        return freeSlotService.suggest(start, end, REMINDER_WINDOW, null).stream()
                .map(FreeSlotService.Slot::startAt)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "提醒日 08:00 到 23:00 沒有可用空檔，請指定其他提醒日或時間"));
    }

    @EventListener
    public void onTaskCompleted(TaskCompletedEvent event) {
        repository.findByTaskId(event.taskId()).ifPresent(plan -> plan.complete(event.completedAt()));
    }

    @EventListener
    public void onTaskCanceled(TaskCanceledEvent event) {
        repository.findByTaskId(event.taskId()).ifPresent(plan -> plan.cancel(event.canceledAt()));
    }

    public record PlannedTask(Task task, FlexibleDayTaskPlan plan, boolean scheduleSuggested) {
    }
}
