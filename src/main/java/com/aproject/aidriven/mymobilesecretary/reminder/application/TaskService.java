package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskStatus;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
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
        Task task = taskRepository.save(Task.create(title, description, priority, dueAt, Instant.now(clock)));
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

    /**
     * 關鍵字找未結案任務(自然語言「牛奶買到了」的閉環用)。
     * 比對是確定性規則:標題包含關鍵字、或關鍵字包含標題;LLM 不得直接指定任務 id。
     */
    @Transactional(readOnly = true)
    public List<Task> findOpenTasksMatching(String keyword) {
        String needle = keyword == null ? "" : keyword.strip();
        if (needle.isEmpty()) {
            return List.of();
        }
        return taskRepository.findByStatusIn(EnumSet.of(
                        TaskStatus.CREATED, TaskStatus.SCHEDULED, TaskStatus.REMINDED, TaskStatus.ESCALATED))
                .stream()
                .filter(task -> task.getTitle().contains(needle) || needle.contains(task.getTitle()))
                .toList();
    }

    /** 確認任務完成。非法狀態轉換由 domain 丟 BusinessException(422)。 */
    public Task confirmTask(Long taskId) {
        Task task = getTask(taskId);
        task.confirm(Instant.now(clock));
        return task;
    }

    /** 取消任務,之後不再追蹤。 */
    public Task cancelTask(Long taskId) {
        Task task = getTask(taskId);
        task.cancel(Instant.now(clock));
        return task;
    }
}
