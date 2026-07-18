package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SchedulePlaceBindingAnswerServiceTest {

    private ScheduleService scheduleService;
    private PlaceService placeService;
    private ConversationContextService contextService;
    private SchedulePlaceBindingAnswerService service;

    @BeforeEach
    void setUp() {
        scheduleService = mock(ScheduleService.class);
        placeService = mock(PlaceService.class);
        contextService = mock(ConversationContextService.class);
        service = new SchedulePlaceBindingAnswerService(
                scheduleService, placeService, contextService);
    }

    @Test
    void correctionQuestionChecksPersistedBindingInsteadOfReturningFeedback() {
        ScheduleItem item = schedule("父親節活動", 9L);
        Place school = Place.create("滬江幼兒園", "臺北市文山區景美街", 25.0, 121.5,
                "SCHOOL", Instant.parse("2026-07-18T00:00:00Z"));
        ReflectionTestUtils.setField(school, "id", 9L);
        when(scheduleService.listSchedules(null)).thenReturn(List.of(item));
        when(contextService.scheduleIdAt(null)).thenReturn(7L);
        when(scheduleService.getSchedule(7L)).thenReturn(item);
        when(placeService.getPlace(9L)).thenReturn(school);

        IntentResult result = service.answer(
                "不對我是問你有沒有把行程正確綁定地點").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_INFO);
        assertThat(result.message()).contains("查過實際儲存資料", "父親節活動",
                "已綁定地點「滬江幼兒園」", "臺北市文山區景美街", "地點 ID｜9");
    }

    @Test
    void missingBindingIsReportedWithoutGuessing() {
        ScheduleItem item = schedule("父親節活動", null);
        when(scheduleService.listSchedules(null)).thenReturn(List.of(item));

        IntentResult result = service.answer("父親節活動行程有綁地點嗎？").orElseThrow();

        assertThat(result.message()).contains("尚未綁定任何地點", "不會自行猜測或綁定");
    }

    @Test
    void multipleSchedulesWithoutContextAsksWhichOne() {
        when(scheduleService.listSchedules(null)).thenReturn(List.of(
                schedule("父親節活動", 9L), schedule("專案週會", 10L)));

        IntentResult result = service.answer("行程有沒有正確綁定地點？").orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("請回覆編號或名稱", "父親節活動", "專案週會",
                "不會新增或修改地點綁定");
    }

    @Test
    void unrelatedLocationQuestionPassesThrough() {
        assertThat(service.answer("我現在的位置在哪裡？")).isEmpty();
    }

    private static ScheduleItem schedule(String title, Long placeId) {
        return ScheduleItem.propose(title,
                Instant.parse("2026-08-08T01:00:00Z"),
                Instant.parse("2026-08-08T03:00:00Z"), placeId,
                Instant.parse("2026-07-18T00:00:00Z"));
    }
}
