package com.aproject.aidriven.mymobilesecretary.reminder.application;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, ReminderScheduleService scheduleService, Clock clock) {
        this.taskRepository = taskRepository;
        this.scheduleService = scheduleService;
        this.clock = clock;
    }

    /**
     * 建立新任務(初始狀態 CREATED)。
     * 有期限的任務自動排入到期提醒;到期時該不該提醒由觸發服務守門
     * (任務屆時已確認/取消就不會響)。
     *
     * @param priority 呼叫端未指定時由 API 層預設 NORMAL
     */
    public Task createTask(String title, String description, TaskPriority priority, Instant dueAt) {
        Task task = taskRepository.save(Task.create(title, description, priority, dueAt, Instant.now(clock)));
        if (dueAt != null) {
            scheduleService.scheduleDueReminder(task.getId(), dueAt);
        }
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
