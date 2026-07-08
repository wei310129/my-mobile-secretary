package com.aproject.aidriven.mymobilesecretary.api.task;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任務 API。只做 HTTP 轉換,所有邏輯在 TaskService 與 domain。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 建立任務 → 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse createTask(@Valid @RequestBody CreateTaskRequest request) {
        return TaskResponse.from(taskService.createTask(
                request.title(), request.description(), request.priorityOrDefault(), request.dueAt()));
    }

    /** 列出全部任務。 */
    @GetMapping
    public List<TaskResponse> listTasks() {
        return taskService.listTasks().stream().map(TaskResponse::from).toList();
    }

    /** 查單一任務;不存在 → 404。 */
    @GetMapping("/{taskId}")
    public TaskResponse getTask(@PathVariable Long taskId) {
        return TaskResponse.from(taskService.getTask(taskId));
    }

    /** 確認任務完成;不存在 → 404,狀態不允許 → 422。 */
    @PatchMapping("/{taskId}/confirm")
    public TaskResponse confirmTask(@PathVariable Long taskId) {
        return TaskResponse.from(taskService.confirmTask(taskId));
    }

    /** 取消任務;不存在 → 404,狀態不允許 → 422。 */
    @PatchMapping("/{taskId}/cancel")
    public TaskResponse cancelTask(@PathVariable Long taskId) {
        return TaskResponse.from(taskService.cancelTask(taskId));
    }
}
