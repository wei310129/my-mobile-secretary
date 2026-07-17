package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
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
class LastActivityAnswerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Mock
    private TaskService taskService;
    @Mock
    private ScheduleService scheduleService;

    @Test
    void answersExactLastExerciseQuestionFromPastConfirmedSchedule() {
        ScheduleItem exercise = ScheduleItem.propose("運動",
                Instant.parse("2026-07-15T12:53:00Z"),
                Instant.parse("2026-07-15T13:53:00Z"), null, NOW.minusSeconds(172800));
        exercise.confirm(NOW.minusSeconds(172700));
        when(scheduleService.listSchedules(null)).thenReturn(List.of(exercise));
        when(taskService.listCompletedTasks()).thenReturn(List.of());
        LastActivityAnswerService service = service();

        IntentResult result = service.answer("我上次運動是什麼時候").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.RECENT_ACTIVITY_LISTED);
        assertThat(result.message())
                .contains("最近一次「運動」", "2026/07/15", "20:53–21:53", "已確認的過往行程")
                .contains("不能確定實際完成");
    }

    @Test
    void exerciseSynonymsMatchAndCompletedRecordDoesNotShowUncertaintyWarning() {
        ScheduleItem workout = ScheduleItem.propose("重訓",
                Instant.parse("2026-07-15T02:00:00Z"),
                Instant.parse("2026-07-15T03:00:00Z"), null, NOW.minusSeconds(172800));
        workout.confirm(NOW.minusSeconds(172700));
        workout.complete(NOW.minusSeconds(86400));
        when(scheduleService.listSchedules(null)).thenReturn(List.of(workout));
        when(taskService.listCompletedTasks()).thenReturn(List.of());

        IntentResult result = service().answer("距離上次運動多久了？").orElseThrow();

        assertThat(result.message()).contains("已完成行程", "重訓").doesNotContain("不能確定");
    }

    @Test
    void completedTaskCanAnswerDurationStyleQuestion() {
        Task exercise = Task.create("做瑜伽", null, TaskPriority.NORMAL, null,
                NOW.minusSeconds(172800));
        exercise.confirm(Instant.parse("2026-07-15T10:30:00Z"));
        when(scheduleService.listSchedules(null)).thenReturn(List.of());
        when(taskService.listCompletedTasks()).thenReturn(List.of(exercise));

        IntentResult result = service().answer("我多久沒運動了").orElseThrow();

        assertThat(result.message()).contains("2026/07/15", "18:30", "已完成待辦");
    }

    @Test
    void unrelatedSentenceIsNotIntercepted() {
        assertThat(LastActivityAnswerService.requestedTopic("明天晚上八點運動"))
                .isEmpty();
        assertThat(LastActivityAnswerService.requestedTopic("我上次買牛奶是什麼時候"))
                .isEmpty();
    }

    @Test
    void feedbackParagraphThatQuotesLastActivityQuestionIsNotIntercepted() {
        String feedback = "而且我上次什麼時候運動是個提問，是要你幫我查之前的行程最後一次"
                + "在什麼時候回報給我，而不是把它當成一個待辦事項來儲存。"
                + "有時候我會問我上一次運動是什麼時候，你還要分辨運動種類。";

        assertThat(LastActivityAnswerService.requestedTopic(feedback)).isEmpty();
    }

    @Test
    void naturalQuestionParticleStillCountsAsStandaloneQuestion() {
        assertThat(LastActivityAnswerService.requestedTopic("我上次運動是什麼時候啊"))
                .contains("運動");
    }

    private LastActivityAnswerService service() {
        return new LastActivityAnswerService(taskService, scheduleService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
