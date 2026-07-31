package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningItemTypeAnswerServiceTest {

    private final TaskService tasks = mock(TaskService.class);
    private final ScheduleService schedules = mock(ScheduleService.class);
    private final PlanningItemTypeAnswerService service =
            new PlanningItemTypeAnswerService(tasks, schedules);

    @Test
    void sameTitleTodoAndTimedReminderAreNumberedAndNamedByActualType() {
        Instant now = Instant.parse("2026-07-20T02:00:00Z");
        Task todo = Task.create("拿包裹", null, TaskPriority.NORMAL, null, now);
        Task reminder = Task.create("拿包裹", null, TaskPriority.NORMAL,
                Instant.parse("2026-07-21T07:00:00Z"), now);
        when(tasks.listOpenTasks()).thenReturn(List.of(reminder, todo));
        when(schedules.listSchedules(null)).thenReturn(List.of());

        IntentResult result = service.answer(
                "「拿包裹」是草稿嗎？還是重複的提醒紀錄？你沒講清楚", null)
                .orElseThrow();

        assertThat(result.message())
                .contains("1. 待辦事項「拿包裹」｜沒有執行日期或時間")
                .contains("2. 行程提醒「拿包裹」｜提醒時間")
                .contains("不參與撞期")
                .doesNotContain("活動草稿");
    }

    @Test
    void unclearPronounExplainsTypesWithoutLettingPendingDraftGuess() {
        when(tasks.listOpenTasks()).thenReturn(List.of());
        when(schedules.listSchedules(null)).thenReturn(List.of());

        IntentResult result = service.answer(
                "這些資料都是草稿嗎？還是提醒事項？如果不同類別就按類別列舉", null)
                .orElseThrow();

        assertThat(result.message()).contains("不會拿目前未完成的草稿代答", "LINE 回覆原清單");
    }

    @Test
    void rankingAndAnalysisQuestionsAreNotMistakenForTypeQuestions() {
        assertThat(PlanningItemTypeAnswerService.asksForType(
                "告訴我下週哪一天最忙、最長的一筆是哪一筆，"
                        + "並列出那筆行程前後還各剩多少空檔"))
                .isFalse();
        assertThat(PlanningItemTypeAnswerService.asksForType(
                "這週最長的行程是哪一筆"))
                .isFalse();
        assertThat(PlanningItemTypeAnswerService.asksForType(
                "哪一天行程最多"))
                .isFalse();
    }

    @Test
    void explicitTypeVocabularyStillRoutesToTheTypeAnswer() {
        assertThat(PlanningItemTypeAnswerService.asksForType(
                "這筆資料是哪一類，是待辦還是行程提醒"))
                .isTrue();
    }
}
