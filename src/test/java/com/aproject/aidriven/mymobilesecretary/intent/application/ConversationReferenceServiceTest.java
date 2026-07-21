package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
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

        String payload = service.capture(IntentResult.message(
                IntentResult.Action.SCHEDULE_INFO, "行程已建立"));

        assertThat(payload).isNull();
    }

    private static ScheduleItem schedule(long id, String title) {
        ScheduleItem item = mock(ScheduleItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getTitle()).thenReturn(title);
        return item;
    }
}
