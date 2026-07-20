package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.reminder.application.DeferredTaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.DeferredTask;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskMutationIntentHandlerTest {

    private TaskService taskService;
    private DeferredTaskService deferredTaskService;
    private TaskMutationIntentHandler handler;

    @BeforeEach
    void setUp() {
        taskService = mock(TaskService.class);
        deferredTaskService = mock(DeferredTaskService.class);
        handler = new TaskMutationIntentHandler(
                taskService,
                deferredTaskService,
                mock(ScheduleService.class),
                mock(ConversationContextService.class),
                mock(PlaceAliasService.class),
                mock(PlaceService.class),
                mock(GeofenceRuleService.class),
                200);
    }

    @Test
    void registersEveryTaskMutationType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.CREATE_TASK,
                IntentCommand.Type.CREATE_FLEXIBLE_DAY_TASK,
                IntentCommand.Type.COMPLETE_TASK,
                IntentCommand.Type.CANCEL_TASK,
                IntentCommand.Type.CANCEL_ALL_TASKS,
                IntentCommand.Type.RESCHEDULE_TASK,
                IntentCommand.Type.CONVERT_TASK_TO_SCHEDULE_REMINDER,
                IntentCommand.Type.CONVERT_TASK_TO_TODO,
                IntentCommand.Type.UPDATE_TASK,
                IntentCommand.Type.PAUSE_RECURRING_TASK,
                IntentCommand.Type.RESUME_RECURRING_TASK,
                IntentCommand.Type.SKIP_RECURRING_OCCURRENCE));
    }

    @Test
    void convertsTodoToNonExclusiveScheduleReminderAtExplicitTime() {
        Task task = mock(Task.class);
        java.time.Instant dueAt = java.time.Instant.parse("2026-07-21T07:00:00Z");
        when(task.getId()).thenReturn(7L);
        when(task.getTitle()).thenReturn("拿包裹");
        when(task.getDueAt()).thenReturn(null);
        when(taskService.findOpenTasksMatching("拿包裹")).thenReturn(List.of(task));
        when(taskService.changeDueDate(7L, dueAt)).thenReturn(task);
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CONVERT_TASK_TO_SCHEDULE_REMINDER, "拿包裹",
                dueAt.toString(), null, null, null, null, null,
                null, null, null, null, null);

        IntentResult result = handler.handle("把拿包裹改成明天下午三點的行程提醒", command);

        assertThat(result.message()).contains("轉成行程提醒", "不占用行程時段", "不參與撞期");
        verify(taskService).changeDueDate(7L, dueAt);
    }

    @Test
    void convertsScheduleReminderToTimelessTodoAndRemovesDueSchedule() {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(8L);
        when(task.getTitle()).thenReturn("拿包裹");
        when(task.getDueAt()).thenReturn(java.time.Instant.parse("2026-07-21T07:00:00Z"));
        when(taskService.findOpenTasksMatching("拿包裹")).thenReturn(List.of(task));
        when(taskService.changeDueDate(8L, null)).thenReturn(task);

        IntentResult result = handler.handle("把拿包裹行程提醒改成待辦事項",
                command(IntentCommand.Type.CONVERT_TASK_TO_TODO, "拿包裹"));

        assertThat(result.message()).contains("轉成待辦事項", "提醒排程已移除");
        verify(taskService).changeDueDate(8L, null);
    }

    @Test
    void duplicateCreateKeepsSafetyGuardAndDoesNotMutate() {
        Task existing = mock(Task.class);
        when(existing.getTitle()).thenReturn("拿包裹");
        when(taskService.findOpenTasksMatching("拿包裹")).thenReturn(List.of(existing));

        IntentResult result = handler.handle("記得拿包裹", command(IntentCommand.Type.CREATE_TASK, "拿包裹"));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("不再重複建立");
        verify(taskService, never()).createTask(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void afterTaskCompletionDefersCreationUntilJavaConfirmsPredecessor() {
        DeferredTask deferred = DeferredTask.waitFor(
                7L, "回家前加油", null,
                com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority.NORMAL,
                0, java.time.Instant.parse("2026-07-18T13:00:00Z"));
        when(deferredTaskService.deferUntilTaskCompleted(
                "拿公司電腦", "回家前加油", null,
                com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority.NORMAL, 0))
                .thenReturn(deferred);

        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "回家前加油", null, null, null,
                null, "NORMAL", null, null, null, null, null, null,
                com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions
                        .empty().afterTaskCompletion("拿公司電腦", 0));

        IntentResult result = handler.handle("拿完電腦提醒我加油", command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.TASK_DEFERRED);
        assertThat(result.message()).contains("只有確認", "拿公司電腦", "回家前加油");
        verify(taskService, never()).createTask(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static IntentCommand command(IntentCommand.Type type, String title) {
        return new IntentCommand(type, title, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
