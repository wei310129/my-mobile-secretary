package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleTaskConflictAnswerServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-15T12:53:00Z");

    @Mock
    private TaskService taskService;

    @Mock
    private ScheduleService scheduleService;

    @Test
    void recordedPlanningQuestionExplainsConflictWithoutChangingData() {
        Task bath = Task.create("帶小孩去洗澡", null, TaskPriority.NORMAL,
                Instant.parse("2026-07-15T13:00:00Z"), CREATED_AT);
        ScheduleItem exercise = ScheduleItem.propose("運動",
                Instant.parse("2026-07-15T12:53:00Z"),
                Instant.parse("2026-07-15T13:53:00Z"), null, CREATED_AT);
        when(taskService.listOpenTasks()).thenReturn(List.of(bath));
        when(scheduleService.listSchedules(null)).thenReturn(List.of(exercise));
        ScheduleTaskConflictAnswerService service = new ScheduleTaskConflictAnswerService(
                taskService, scheduleService);

        IntentResult result = service.answer("我九點要帶小孩去洗澡要怎麼去運動？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_NEEDS_DECISION);
        assertThat(result.message())
                .contains("運動", "07/15 20:53", "21:53", "帶小孩去洗澡", "07/15 21:00")
                .contains("縮短到 21:00 前", "改到「帶小孩去洗澡」之後")
                .contains("⚠️ 目前有時間衝突", "💡 建議可選擇的安排")
                .contains("不會自行更動", "不會另建一個同名行程");
    }

    @Test
    void explicitRescheduleCommandIsLeftForIntentCommandHandling() {
        ScheduleTaskConflictAnswerService service = new ScheduleTaskConflictAnswerService(
                taskService, scheduleService);

        assertThat(service.answer("運動改到洗澡之後")).isEmpty();
        verifyNoInteractions(taskService, scheduleService);
    }
}
