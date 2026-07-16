package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReminderTimingAnswerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T00:30:00Z");

    @Mock
    private TaskService taskService;

    @Test
    void answersRecordedBathReminderQuestionWithOriginalOverdueTime() {
        Task task = Task.create("帶小孩去洗澡", null, TaskPriority.NORMAL,
                Instant.parse("2026-07-15T13:00:00Z"), NOW.minusSeconds(3600));
        when(taskService.listTasks()).thenReturn(List.of(task));
        ReminderTimingAnswerService service = new ReminderTimingAnswerService(
                taskService, Clock.fixed(NOW, ZoneOffset.UTC));

        IntentResult result = service.answer("那你會在什麼時機提醒我要帶小孩去洗澡？").orElseThrow();

        assertThat(result.message())
                .contains("07/15 21:00", "已逾期", "不會自行改成今天早上", "請告訴我要改到何時");
    }

    @Test
    void scheduleReminderTaskCanBeFoundWithoutInternalTitlePrefix() {
        Task task = Task.create("提醒:專案週會", null, TaskPriority.NORMAL,
                Instant.parse("2026-07-17T01:50:00Z"), NOW);
        when(taskService.listTasks()).thenReturn(List.of(task));
        ReminderTimingAnswerService service = new ReminderTimingAnswerService(
                taskService, Clock.fixed(NOW, ZoneOffset.UTC));

        IntentResult result = service.answer("專案週會什麼時候提醒？").orElseThrow();

        assertThat(result.message()).contains("專案週會", "07/17 09:50", "尚未到");
    }

    @Test
    void reminderCreationOrRescheduleIsNotIntercepted() {
        assertThat(ReminderTimingAnswerService.isTimingQuestion("今晚九點提醒我洗澡")).isFalse();
        assertThat(ReminderTimingAnswerService.isTimingQuestion("提醒時間改到明天九點")).isFalse();
    }
}
