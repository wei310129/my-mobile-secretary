package com.aproject.aidriven.mymobilesecretary.api.schedule;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 行程 API 整合測試:「要可行才放行」的完整流程。
 *
 * 隔離策略:各測試用互不重疊的 2027 年遠期時段,避免共用資料庫互撞;
 * 只有「高雄→台北」情境用近期時間(它就是要測從目前位置趕不到)。
 */
class ScheduleApiTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private long createPlace(String name, double lat, double lon) throws Exception {
        String body = mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "latitude": %f, "longitude": %f}
                                """.formatted(name, lat, lon)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private String createSchedule(String title, String startAt, String endAt, Long placeId) throws Exception {
        String placePart = placeId == null ? "" : ", \"placeId\": %d".formatted(placeId);
        return mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s", "startAt": "%s", "endAt": "%s"%s}
                                """.formatted(title, startAt, endAt, placePart)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** 可行 → 自動 CONFIRMED(放行)。 */
    @Test
    void feasibleScheduleIsAutoConfirmed() throws Exception {
        String body = createSchedule("剪頭髮", "2027-03-01T03:00:00Z", "2027-03-01T04:00:00Z", null);

        var json = objectMapper.readTree(body);
        org.assertj.core.api.Assertions.assertThat(json.get("feasible").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.get("schedule").get("status").asText())
                .isEqualTo("CONFIRMED");
    }

    /** 時間重疊 → 留 PROPOSED + TIME_OVERLAP + 選項;改時間後放行。 */
    @Test
    void overlappingScheduleStaysProposedThenRescheduleConfirms() throws Exception {
        createSchedule("開會", "2027-03-02T03:00:00Z", "2027-03-02T05:00:00Z", null);

        String body = createSchedule("剪頭髮", "2027-03-02T04:00:00Z", "2027-03-02T06:00:00Z", null);
        var json = objectMapper.readTree(body);
        org.assertj.core.api.Assertions.assertThat(json.get("feasible").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(json.get("schedule").get("status").asText())
                .isEqualTo("PROPOSED");
        org.assertj.core.api.Assertions.assertThat(json.get("issues").get(0).get("type").asText())
                .isEqualTo("TIME_OVERLAP");
        org.assertj.core.api.Assertions.assertThat(json.get("options")).isNotEmpty();

        // 改到不重疊的時間 → 重新驗算 → 放行
        long id = json.get("schedule").get("id").asLong();
        mockMvc.perform(patch("/api/schedules/{id}/reschedule", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startAt": "2027-03-02T06:00:00Z", "endAt": "2027-03-02T07:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.schedule.status").value("CONFIRMED"));
    }

    /** 使用者原始案例:人在高雄,2 小時後台北的預約 → 擋下;強制確認可放行。 */
    @Test
    void kaohsiungTaipeiScenarioIsGatedThenForceConfirmed() throws Exception {
        long taipeiPlace = createPlace("台北理髮廳", 25.0330, 121.5654);
        // 回報人在高雄
        mockMvc.perform(post("/api/location-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType": "MANUAL_PING", "latitude": 22.6120, "longitude": 120.3000}
                                """))
                .andExpect(status().isCreated());

        String start = java.time.Instant.now().plus(java.time.Duration.ofHours(2)).toString();
        String end = java.time.Instant.now().plus(java.time.Duration.ofHours(3)).toString();
        String body = createSchedule("剪頭髮", start, end, taipeiPlace);

        var json = objectMapper.readTree(body);
        org.assertj.core.api.Assertions.assertThat(json.get("feasible").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThat(json.get("issues").get(0).get("type").asText())
                .isEqualTo("TRAVEL_FROM_CURRENT_LOCATION");

        // 使用者說「我已安排好交通」→ 強制確認
        long id = json.get("schedule").get("id").asLong();
        mockMvc.perform(patch("/api/schedules/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    /** 暫無想法 → pending 池,可用 ?status=PENDING 查。 */
    @Test
    void parkedScheduleAppearsInPendingPool() throws Exception {
        createSchedule("卡位用", "2027-03-03T03:00:00Z", "2027-03-03T05:00:00Z", null);
        String body = createSchedule("待定聚會", "2027-03-03T04:00:00Z", "2027-03-03T06:00:00Z", null);
        long id = objectMapper.readTree(body).get("schedule").get("id").asLong();

        mockMvc.perform(patch("/api/schedules/{id}/park", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        String pending = mockMvc.perform(get("/api/schedules").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(
                objectMapper.readTree(pending).findValuesAsText("id")).contains(String.valueOf(id));
    }

    /** endAt 不晚於 startAt → 422。 */
    @Test
    void invalidTimeRangeReturns422() throws Exception {
        mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "倒退嚕", "startAt": "2027-03-04T05:00:00Z", "endAt": "2027-03-04T04:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    /** 地點不存在 → 404。 */
    @Test
    void unknownPlaceReturns404() throws Exception {
        mockMvc.perform(post("/api/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "幽靈地點", "startAt": "2027-03-05T03:00:00Z", "endAt": "2027-03-05T04:00:00Z", "placeId": 999999}
                                """))
                .andExpect(status().isNotFound());
    }
}
