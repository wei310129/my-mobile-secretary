package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskDetailAnswerServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T03:30:00Z");

    @Mock
    private TaskService taskService;

    @Mock
    private GeofenceRuleService geofenceRuleService;

    @Mock
    private PlaceService placeService;

    private TaskDetailAnswerService service;

    @BeforeEach
    void setUp() {
        service = new TaskDetailAnswerService(taskService, geofenceRuleService, placeService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recordedMilkTaskDetailQuestionReportsMissingPlace() {
        Task milk = Task.create("買小兒子的奶粉", null, TaskPriority.HIGH,
                Instant.parse("2026-07-19T15:59:59Z"), NOW.minusSeconds(60));
        when(taskService.listTasks()).thenReturn(List.of(milk));
        when(geofenceRuleService.listRulesForTask(null)).thenReturn(List.of());

        IntentResult result = service.answer("列出買小兒子奶粉的明細").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.TASK_INFO);
        assertThat(result.message())
                .contains("📋 待辦「買小兒子的奶粉」明細", "期限｜07/19 23:59｜尚未到")
                .contains("優先度｜高", "狀態｜已建立", "重複｜單次", "地點｜尚未紀錄")
                .contains("⚠️ 目前沒有綁定地點");
    }

    @Test
    void taskDetailIncludesBoundPlaceAndAddress() {
        Task milk = Task.create("買小兒子的奶粉", null, TaskPriority.HIGH,
                Instant.parse("2026-07-19T15:59:59Z"), NOW.minusSeconds(60));
        GeofenceRule rule = GeofenceRule.create(1L, 2L, 150, TriggerType.ENTER, NOW);
        Place pharmacy = Place.create("景美大樹藥局", "台北市文山區景後街", 25.0, 121.0,
                "藥局", NOW);
        when(taskService.listTasks()).thenReturn(List.of(milk));
        when(geofenceRuleService.listRulesForTask(null)).thenReturn(List.of(rule));
        when(placeService.getPlace(2L)).thenReturn(pharmacy);

        IntentResult result = service.answer("買小兒子的奶粉詳細資訊").orElseThrow();

        assertThat(result.message()).contains("地點｜景美大樹藥局｜台北市文山區景後街")
                .doesNotContain("沒有綁定地點");
    }

    @Test
    void priceHistoryQuestionIsNotIntercepted() {
        assertThat(service.answer("奶粉上次買多少錢的明細")).isEmpty();
        verifyNoInteractions(taskService, geofenceRuleService, placeService);
    }

    @Test
    void taskCreationConfirmationShowsStoredDeadlinePriorityAndPlace() {
        Task milk = Task.create("買小兒子的奶粉", null, TaskPriority.HIGH,
                Instant.parse("2026-07-19T15:59:59Z"), NOW);
        Place pharmacy = Place.create("景美大樹藥局", "台北市文山區景後街", 25.0, 121.0,
                "藥局", NOW);

        IntentResult result = IntentResult.taskCreated(milk, null, pharmacy);

        assertThat(result.message())
                .contains("📋 已建立任務「買小兒子的奶粉」")
                .contains("期限｜07/19 23:59", "優先度｜高", "地點｜景美大樹藥局");
    }
}
