package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任務 use case:建立、查詢、確認完成、取消。
 * 狀態轉換規則在 Task domain 內,這裡只負責流程編排與交易邊界。
 */
@Service
@Transactional
public class TaskService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final TaskRepository taskRepository;
    private final ReminderScheduleService scheduleService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReminderScheduleService scheduleService,
                       ApplicationEventPublisher eventPublisher, Clock clock) {
        this.taskRepository = taskRepository;
        this.scheduleService = scheduleService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 建立新任務(初始狀態 CREATED)。
     * 有期限的任務自動排入到期提醒;到期時該不該提醒由觸發服務守門
     * (任務屆時已確認/取消就不會響)。
     * 發佈 TaskCreatedEvent 供其他模組反應(如 knowledge 的品項自動綁定)。
     *
     * @param priority 呼叫端未指定時由 API 層預設 NORMAL
     */
    public Task createTask(String title, String description, TaskPriority priority, Instant dueAt) {
        return createTask(title, description, priority, dueAt,
                Task.Category.OTHER, Task.Recurrence.NONE, Task.ConditionType.NONE, null);
    }

    /** 建立含分類、週期與條件的任務。 */
    public Task createTask(String title, String description, TaskPriority priority, Instant dueAt,
                           Task.Category category, Task.Recurrence recurrence,
                           Task.ConditionType conditionType, String conditionPayload) {
        if (recurrence != null && recurrence != Task.Recurrence.NONE && dueAt == null) {
            throw new IllegalArgumentException("recurring task requires dueAt");
        }
        if (conditionType != null && conditionType != Task.ConditionType.NONE && dueAt == null) {
            throw new IllegalArgumentException("conditional task requires dueAt");
        }
        Task task = taskRepository.save(Task.create(title, description, priority, dueAt,
                category, recurrence, conditionType, conditionPayload, Instant.now(clock)));
        if (dueAt != null) {
            scheduleService.scheduleDueReminder(task.getId(), dueAt);
        }
        // 同交易的同步事件:回應返回前自動綁定已完成
        eventPublisher.publishEvent(new TaskCreatedEvent(task.getId(), task.getTitle()));
        return task;
    }

    @Transactional(readOnly = true)
    public List<Task> listTasks() {
        return taskRepository.findAll();
    }

    /** 查單一任務;不存在丟 NotFoundException(404)。 */
    @Transactional(readOnly = true)
    public Task getTask(Long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("Task", taskId));
    }

    /** 未結案任務清單(自然語言「還有什麼要做」用);有期限的排前面。 */
    @Transactional(readOnly = true)
    public List<Task> listOpenTasks() {
        return taskRepository.findByStatusIn(EnumSet.of(
                        TaskStatus.CREATED, TaskStatus.SCHEDULED, TaskStatus.REMINDED, TaskStatus.ESCALATED))
                .stream()
                .sorted(java.util.Comparator.comparing(Task::getDueAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /** 已完成待辦,最近完成的排前面。 */
    @Transactional(readOnly = true)
    public List<Task> listCompletedTasks() {
        return taskRepository.findByStatusIn(EnumSet.of(TaskStatus.CONFIRMED)).stream()
                .sorted(java.util.Comparator.comparing(Task::getUpdatedAt).reversed())
                .toList();
    }

    /**
     * 關鍵字找未結案任務(自然語言「牛奶買到了」的閉環用)。
     * 比對是確定性規則:標題包含關鍵字、或關鍵字包含標題;LLM 不得直接指定任務 id。
     * 不分大小寫——英文任務(「Buy soy sauce」)才不會因大小寫對不上。
     */
    @Transactional(readOnly = true)
    public List<Task> findOpenTasksMatching(String keyword) {
        String needle = keyword == null ? "" : keyword.strip().toLowerCase();
        if (needle.isEmpty()) {
            return List.of();
        }
        return taskRepository.findByStatusIn(EnumSet.of(
                        TaskStatus.CREATED, TaskStatus.SCHEDULED, TaskStatus.REMINDED, TaskStatus.ESCALATED))
                .stream()
                .filter(task -> {
                    String title = task.getTitle().toLowerCase();
                    return title.contains(needle) || needle.contains(title);
                })
                .toList();
    }

    /** 一次取消全部未結案任務(「全部待辦都取消」);回傳被取消的清單供回覆。 */
    public List<Task> cancelAllOpenTasks() {
        List<Task> open = taskRepository.findByStatusIn(EnumSet.of(
                TaskStatus.CREATED, TaskStatus.SCHEDULED, TaskStatus.REMINDED, TaskStatus.ESCALATED));
        Instant now = Instant.now(clock);
        open.forEach(task -> {
            task.cancel(now);
            scheduleService.removeDueReminder(task.getId());
        });
        return open;
    }

    /** 確認任務完成。非法狀態轉換由 domain 丟 BusinessException(422)。 */
    public Task confirmTask(Long taskId) {
        Task task = getTask(taskId);
        if (task.getRecurrence() != Task.Recurrence.NONE) {
            Instant now = Instant.now(clock);
            // The due worker advances a recurring task as soon as an occurrence is delivered.
            // A later "done" acknowledgement must not skip the already-scheduled next occurrence.
            if (task.getStatus() == TaskStatus.SCHEDULED
                    && task.getDueAt() != null
                    && task.getDueAt().isAfter(now)) {
                return task;
            }
            Instant next = nextOccurrence(task, now);
            task.advanceOccurrence(next, now);
            if (!task.isRecurrencePaused()) {
                scheduleService.scheduleDueReminder(taskId, next);
            }
            return task;
        }
        task.confirm(Instant.now(clock));
        scheduleService.removeDueReminder(taskId);
        return task;
    }

    /** 取消任務,之後不再追蹤。 */
    public Task cancelTask(Long taskId) {
        Task task = getTask(taskId);
        task.cancel(Instant.now(clock));
        scheduleService.removeDueReminder(taskId);
        return task;
    }

    /**
     * 改任務期限,並同步到期提醒排程:
     * 有新期限 → 重排(同任務排程只更新時間);清空期限 → 移除排程,否則會在舊時間亂響。
     */
    public Task changeDueDate(Long taskId, Instant newDueAt) {
        Task task = getTask(taskId);
        task.changeDueAt(newDueAt, Instant.now(clock));
        if (newDueAt != null && !task.isRecurrencePaused()) {
            scheduleService.scheduleDueReminder(taskId, newDueAt);
        } else {
            scheduleService.removeDueReminder(taskId);
        }
        return task;
    }

    /** 修改名稱、備註、優先級或分類。null 欄位維持原值。 */
    public Task updateTask(Long taskId, String newTitle, String description,
                           TaskPriority priority, Task.Category category) {
        Task task = getTask(taskId);
        task.updateDetails(newTitle, description, priority, category, Instant.now(clock));
        return task;
    }

    public Task pauseRecurrence(Long taskId) {
        Task task = getTask(taskId);
        task.pauseRecurrence(Instant.now(clock));
        scheduleService.removeDueReminder(taskId);
        return task;
    }

    public Task resumeRecurrence(Long taskId) {
        Task task = getTask(taskId);
        Instant now = Instant.now(clock);
        task.resumeRecurrence(now);
        Instant due = task.getDueAt();
        if (due == null || !due.isAfter(now)) {
            due = nextOccurrence(task, now);
            task.advanceOccurrence(due, now);
        }
        scheduleService.scheduleDueReminder(taskId, due);
        return task;
    }

    /** 略過本次但保留整條週期規則。 */
    public Task skipRecurringOccurrence(Long taskId) {
        Task task = getTask(taskId);
        if (task.isRecurrencePaused()) {
            throw new IllegalArgumentException("recurring task is paused");
        }
        Instant now = Instant.now(clock);
        Instant next = nextOccurrence(task, now);
        task.advanceOccurrence(next, now);
        scheduleService.scheduleDueReminder(taskId, next);
        return task;
    }

    /**
     * 週期任務本輪提醒送出後先排下一輪。一般任務不動。
     * 這能避免使用者漏按完成後,隔天／隔週的固定提醒整條消失。
     */
    public Task scheduleNextRecurringOccurrence(Long taskId) {
        Task task = getTask(taskId);
        if (task.getRecurrence() == Task.Recurrence.NONE) {
            return task;
        }
        if (task.isRecurrencePaused()) {
            scheduleService.removeDueReminder(taskId);
            return task;
        }
        Instant now = Instant.now(clock);
        Instant next = nextOccurrence(task, now);
        task.advanceOccurrence(next, now);
        scheduleService.scheduleDueReminder(taskId, next);
        return task;
    }

    /** 條件不成立時結束單次條件任務,避免之後被當成一般待辦催促。 */
    public Task skipConditionalTask(Long taskId) {
        Task task = getTask(taskId);
        if (task.getConditionType() != Task.ConditionType.NONE
                && task.getStatus() != TaskStatus.CANCELED
                && task.getStatus() != TaskStatus.CONFIRMED) {
            task.cancel(Instant.now(clock));
        }
        return task;
    }

    private Instant nextOccurrence(Task task, Instant now) {
        ZonedDateTime base = ZonedDateTime.ofInstant(
                task.getDueAt() == null ? now : task.getDueAt(), TAIPEI);
        ZonedDateTime next = switch (task.getRecurrence()) {
            case DAILY -> base.plusDays(1);
            case WEEKLY -> base.plusWeeks(1);
            case MONTHLY -> base.plusMonths(1);
            case NONE -> base;
        };
        while (!next.toInstant().isAfter(now)) {
            next = switch (task.getRecurrence()) {
                case DAILY -> next.plusDays(1);
                case WEEKLY -> next.plusWeeks(1);
                case MONTHLY -> next.plusMonths(1);
                case NONE -> next;
            };
            if (task.getRecurrence() == Task.Recurrence.NONE) {
                break;
            }
        }
        return next.toInstant();
    }
}
