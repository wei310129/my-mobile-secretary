package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActivityCountAnswerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock
    private TaskService taskService;
    @Mock
    private ScheduleService scheduleService;

    @Test
    void countsLastMonthExerciseAndSeparatesUnverifiedSchedules() {
        ScheduleItem completed = schedule("游泳", "2026-06-05T10:00:00Z", "2026-06-05T11:00:00Z");
        completed.confirm(Instant.parse("2026-06-01T00:00:00Z"));
        completed.complete(Instant.parse("2026-06-05T11:10:00Z"));
        ScheduleItem unverified = schedule("重訓", "2026-06-20T10:00:00Z", "2026-06-20T11:00:00Z");
        unverified.confirm(Instant.parse("2026-06-01T00:00:00Z"));
        ScheduleItem thisMonth = schedule("運動", "2026-07-02T10:00:00Z", "2026-07-02T11:00:00Z");
        thisMonth.confirm(Instant.parse("2026-07-01T00:00:00Z"));
        when(scheduleService.listSchedules(null)).thenReturn(List.of(completed, unverified, thisMonth));
        when(taskService.listCompletedTasks()).thenReturn(List.of());

        IntentResult result = service().answer("我上個月運動幾次？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.ACTIVITY_COUNT_INFO);
        assertThat(result.message())
                .contains("上個月「運動」", "2026/06/01–2026/06/30", "總計｜2 次")
                .contains("已完成回報｜1 次", "只有已確認行程｜1 次", "不保證每次都實際完成");
    }

    @Test
    void noHistoryReturnsZeroWithoutCreatingAnything() {
        when(scheduleService.listSchedules(null)).thenReturn(List.of());
        when(taskService.listCompletedTasks()).thenReturn(List.of());

        IntentResult result = service().answer("本週健身有幾次").orElseThrow();

        assertThat(result.message()).contains("總計｜0 次", "這段期間沒有符合的紀錄");
    }

    @Test
    void purchaseCountQuestionIsNotIntercepted() {
        assertThat(ActivityCountAnswerService.parse("我上個月買牛奶幾次？",
                Clock.fixed(NOW, ZoneOffset.UTC))).isEmpty();
        assertThat(ActivityCountAnswerService.parse("我上個月運動做了幾次？",
                Clock.fixed(NOW, ZoneOffset.UTC))).get()
                .extracting(ActivityCountAnswerService.CountQuery::topic)
                .isEqualTo("運動");
    }

    private ActivityCountAnswerService service() {
        return new ActivityCountAnswerService(taskService, scheduleService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ScheduleItem schedule(String title, String start, String end) {
        return ScheduleItem.propose(title, Instant.parse(start), Instant.parse(end), null,
                Instant.parse("2026-05-01T00:00:00Z"));
    }
}
