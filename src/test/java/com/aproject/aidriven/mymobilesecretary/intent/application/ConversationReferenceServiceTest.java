package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationReferenceServiceTest {

    @Test
    void capturesOnlyRenderedSchedulesInDisplayOrder() {
        ConversationContextService context = mock(ConversationContextService.class);
        ScheduleService schedules = mock(ScheduleService.class);
        TaskService tasks = mock(TaskService.class);
        ScheduleItem first = schedule(14L, "送女兒到夏恩英語上課");
        ScheduleItem second = schedule(16L, "女兒上夏恩英語");
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                null, 16L, null, List.of(), List.of(14L, 16L), null, null, null));
        when(schedules.getSchedule(14L)).thenReturn(first);
        when(schedules.getSchedule(16L)).thenReturn(second);
        ConversationReferenceService service = new ConversationReferenceService(
                context, schedules, tasks);

        String payload = service.capture(IntentResult.message(IntentResult.Action.SCHEDULE_INFO,
                "找到：行程（待確認）「女兒上夏恩英語」"));

        assertThat(payload).isEqualTo("SCHEDULE:16:1");
    }

    @Test
    void staleReferenceDoesNotBreakSuccessfulReply() {
        ConversationContextService context = mock(ConversationContextService.class);
        ScheduleService schedules = mock(ScheduleService.class);
        TaskService tasks = mock(TaskService.class);
        when(context.snapshot()).thenThrow(new IllegalStateException("stale context"));
        ConversationReferenceService service = new ConversationReferenceService(
                context, schedules, tasks);
        IntentResult result = IntentResult.message(
                IntentResult.Action.SCHEDULE_INFO, "行程已建立");

        String payload = service.capture(result);

        assertThat(payload).isNull();
        assertThat(result.message()).doesNotContain(
                "stale context", "SCHEDULE:", "TASK:", "ConversationReferenceService");
    }

    @Test
    void scheduleReplyDoesNotCaptureSameTitleTaskReference() {
        ConversationContextService context = mock(ConversationContextService.class);
        ScheduleService schedules = mock(ScheduleService.class);
        TaskService tasks = mock(TaskService.class);
        ScheduleItem schedule = schedule(16L, "女兒上夏恩英語");
        Task task = mock(Task.class);
        when(task.getTitle()).thenReturn("女兒上夏恩英語");
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                21L, 16L, null, List.of(21L), List.of(16L), null, null, null));
        when(schedules.getSchedule(16L)).thenReturn(schedule);
        when(tasks.getTask(21L)).thenReturn(task);
        ConversationReferenceService service = new ConversationReferenceService(
                context, schedules, tasks);

        String payload = service.capture(IntentResult.message(IntentResult.Action.SCHEDULE_INFO,
                "找到行程「女兒上夏恩英語」"));

        assertThat(payload).isEqualTo("SCHEDULE:16:1");
    }

    @Test
    void taskReplyDoesNotCaptureSameTitleScheduleReference() {
        ConversationContextService context = mock(ConversationContextService.class);
        ScheduleService schedules = mock(ScheduleService.class);
        TaskService tasks = mock(TaskService.class);
        ScheduleItem schedule = schedule(16L, "女兒上夏恩英語");
        Task task = mock(Task.class);
        when(task.getTitle()).thenReturn("女兒上夏恩英語");
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                21L, 16L, null, List.of(21L), List.of(16L), null, null, null));
        when(schedules.getSchedule(16L)).thenReturn(schedule);
        when(tasks.getTask(21L)).thenReturn(task);
        ConversationReferenceService service = new ConversationReferenceService(
                context, schedules, tasks);

        String payload = service.capture(IntentResult.message(
                IntentResult.Action.TASK_INFO, "找到待辦「女兒上夏恩英語」"));

        assertThat(payload).isEqualTo("TASK:21:1");
    }

    @Test
    void mixedAgendaReplyCanCaptureRenderedTasksAndSchedules() {
        ConversationContextService context = mock(ConversationContextService.class);
        ScheduleService schedules = mock(ScheduleService.class);
        TaskService tasks = mock(TaskService.class);
        ScheduleItem schedule = schedule(16L, "女兒上夏恩英語");
        Task task = mock(Task.class);
        when(task.getTitle()).thenReturn("繳英文課學費");
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                21L, 16L, null, List.of(21L), List.of(16L), null, null, null));
        when(schedules.getSchedule(16L)).thenReturn(schedule);
        when(tasks.getTask(21L)).thenReturn(task);
        ConversationReferenceService service = new ConversationReferenceService(
                context, schedules, tasks);

        String payload = service.capture(IntentResult.message(IntentResult.Action.AGENDA_LISTED,
                "1. 待辦「繳英文課學費」\n2. 行程「女兒上夏恩英語」"));

        assertThat(payload).isEqualTo("TASK:21:1;SCHEDULE:16:2");
    }

    @Test
    void typedReplyKeepsExplicitlyRenderedRelatedKindWithDifferentTitle() {
        ConversationContextService context = mock(ConversationContextService.class);
        ScheduleService schedules = mock(ScheduleService.class);
        TaskService tasks = mock(TaskService.class);
        ScheduleItem schedule = schedule(16L, "女兒上夏恩英語");
        Task task = mock(Task.class);
        when(task.getTitle()).thenReturn("繳英文課學費");
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                21L, 16L, null, List.of(21L), List.of(16L), null, null, null));
        when(schedules.getSchedule(16L)).thenReturn(schedule);
        when(tasks.getTask(21L)).thenReturn(task);
        ConversationReferenceService service = new ConversationReferenceService(
                context, schedules, tasks);

        String payload = service.capture(IntentResult.message(IntentResult.Action.TASK_INFO,
                "待辦「繳英文課學費」與行程「女兒上夏恩英語」時間衝突"));

        assertThat(payload).isEqualTo("TASK:21:1;SCHEDULE:16:2");
    }

    private static ScheduleItem schedule(long id, String title) {
        ScheduleItem item = mock(ScheduleItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getTitle()).thenReturn(title);
        return item;
    }
}
