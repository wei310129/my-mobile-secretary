package com.aproject.aidriven.mymobilesecretary.api.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 位置事件 API 整合測試:事件 → PostGIS 命中 → 提醒觸發的完整閉環。
 *
 * 座標策略:每個測試用互不重疊的座標區(相距 >0.1 度 ≈ 11 公里),
 * 因為所有測試共用同一個資料庫,避免彼此的 geofence 規則互相干擾。
 */
class LocationEventApiTest extends IntegrationTestBase {

    /** 約 55 公尺的緯度差:落在 200 公尺半徑內。 */
    private static final double NEAR_OFFSET = 0.0005;
    /** 約 5.5 公里的緯度差:遠超出半徑。 */
    private static final double FAR_OFFSET = 0.05;

    @Autowired
    private ObjectMapper objectMapper;

    private long createTask(String title) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s"}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

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

    private void createRule(long taskId, long placeId, int radius, String triggerType) throws Exception {
        mockMvc.perform(post("/api/tasks/{taskId}/geofence-rules", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "radiusMeters": %d, "triggerType": "%s"}
                                """.formatted(placeId, radius, triggerType)))
                .andExpect(status().isCreated());
    }

    /** 回報事件並回傳觸發的提醒 id 數量。 */
    private JsonNode postEvent(String eventType, double lat, double lon) throws Exception {
        String body = mockMvc.perform(post("/api/location-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType": "%s", "latitude": %f, "longitude": %f, "source": "api-simulated"}
                                """.formatted(eventType, lat, lon)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String getTaskStatus(long taskId) throws Exception {
        String body = mockMvc.perform(get("/api/tasks/{id}", taskId))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("status").asText();
    }

    @Test
    void missingEventTypeReturns400() throws Exception {
        mockMvc.perform(post("/api/location-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 25.0, "longitude": 121.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** 驗收核心:模擬進入綁定地點半徑內 → 建立提醒、任務轉 REMINDED。 */
    @Test
    void enterInsideRadiusTriggersReminderAndMovesTaskToReminded() throws Exception {
        double base = 24.10;
        long taskId = createTask("買排骨");
        long placeId = createPlace("全聯", base, 120.60);
        createRule(taskId, placeId, 200, "ENTER");

        JsonNode result = postEvent("ENTER", base + NEAR_OFFSET, 120.60);

        assertThat(result.get("triggeredReminderIds")).hasSize(1);
        assertThat(getTaskStatus(taskId)).isEqualTo("REMINDED");
    }

    /** 未命中(5.5 公里外)→ 不建立提醒,任務維持 CREATED。 */
    @Test
    void enterFarAwayDoesNotTrigger() throws Exception {
        double base = 24.30;
        long taskId = createTask("買白蘿蔔");
        long placeId = createPlace("菜市場", base, 120.60);
        createRule(taskId, placeId, 200, "ENTER");

        JsonNode result = postEvent("ENTER", base + FAR_OFFSET, 120.60);

        assertThat(result.get("triggeredReminderIds")).isEmpty();
        assertThat(getTaskStatus(taskId)).isEqualTo("CREATED");
    }

    /** 觸發方向必須相符:EXIT 規則不吃 ENTER 事件,反之亦然。 */
    @Test
    void exitRuleOnlyMatchesExitEvents() throws Exception {
        double base = 24.50;
        long taskId = createTask("離家丟舊衣");
        long placeId = createPlace("我家", base, 120.60);
        createRule(taskId, placeId, 200, "EXIT");

        JsonNode enterResult = postEvent("ENTER", base + NEAR_OFFSET, 120.60);
        assertThat(enterResult.get("triggeredReminderIds")).isEmpty();

        JsonNode exitResult = postEvent("EXIT", base + NEAR_OFFSET, 120.60);
        assertThat(exitResult.get("triggeredReminderIds")).hasSize(1);
    }

    /** MANUAL_PING 視同 ENTER:手動回報「我在這」要能命中 ENTER 規則。 */
    @Test
    void manualPingMatchesEnterRule() throws Exception {
        double base = 24.70;
        long taskId = createTask("買 Tabasco");
        long placeId = createPlace("家樂福", base, 120.60);
        createRule(taskId, placeId, 200, "ENTER");

        JsonNode result = postEvent("MANUAL_PING", base + NEAR_OFFSET, 120.60);

        assertThat(result.get("triggeredReminderIds")).hasSize(1);
    }

    /** debounce:視窗內重複進入,第二次不再建立提醒。 */
    @Test
    void duplicateEnterWithinWindowIsDebounced() throws Exception {
        double base = 24.90;
        long taskId = createTask("買蛤蜊");
        long placeId = createPlace("市場", base, 120.60);
        createRule(taskId, placeId, 200, "ENTER");

        JsonNode first = postEvent("ENTER", base + NEAR_OFFSET, 120.60);
        assertThat(first.get("triggeredReminderIds")).hasSize(1);

        JsonNode second = postEvent("ENTER", base + NEAR_OFFSET, 120.60);
        assertThat(second.get("triggeredReminderIds")).isEmpty();
        // 任務仍是 REMINDED(第一次觸發的結果),不會被 debounce 改壞
        assertThat(getTaskStatus(taskId)).isEqualTo("REMINDED");
    }

    /** 已確認完成的任務,再進入地點也不提醒。 */
    @Test
    void confirmedTaskIsNotReminded() throws Exception {
        double base = 23.90;
        long taskId = createTask("已完成的採買");
        long placeId = createPlace("超市", base, 120.60);
        createRule(taskId, placeId, 200, "ENTER");
        mockMvc.perform(patch("/api/tasks/{id}/confirm", taskId)).andExpect(status().isOk());

        JsonNode result = postEvent("ENTER", base + NEAR_OFFSET, 120.60);

        assertThat(result.get("triggeredReminderIds")).isEmpty();
        assertThat(getTaskStatus(taskId)).isEqualTo("CONFIRMED");
    }

    @Test
    void listEventsReturnsRecordedEvents() throws Exception {
        postEvent("MANUAL_PING", 23.70, 120.60);

        mockMvc.perform(get("/api/location-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].eventType").exists())
                .andExpect(jsonPath("$[0].source").value("api-simulated"));
    }
}
