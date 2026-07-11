package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Phase 2A 驗收:品項知識 → 建任務自動綁定 → 到地點觸發提醒的完整閉環。
 * 這條鏈取代了使用者手動 bind。
 */
class AutoBindFlowTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private long createPlace(String name, double lat) throws Exception {
        String body = mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "latitude": %f, "longitude": 120.10}
                                """.formatted(name, lat)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void createItem(String name, long... placeIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (long id : placeIds) {
            if (ids.length() > 0) ids.append(",");
            ids.append(id);
        }
        mockMvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "placeIds": [%s]}
                                """.formatted(name, ids)))
                .andExpect(status().isCreated());
    }

    private JsonNode createTask(String title) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s"}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode rulesOf(long taskId) throws Exception {
        String body = mockMvc.perform(get("/api/tasks/{id}/geofence-rules", taskId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** 驗收核心:登錄「排骨→兩家店」,建「買排骨」→ 兩家店自動綁好,到店即觸發。 */
    @Test
    void taskMentioningItemIsAutoBoundAndTriggersOnArrival() throws Exception {
        double lat = 20.60;
        long store1 = createPlace("自動綁定-店1", lat);
        long store2 = createPlace("自動綁定-店2", 20.62);
        createItem("測試排骨", store1, store2);

        long taskId = createTask("買測試排骨").get("id").asLong();

        // 兩家店都自動綁上(不需手動 bind)
        JsonNode rules = rulesOf(taskId);
        assertThat(rules).hasSize(2);
        assertThat(rules.findValuesAsText("placeId"))
                .containsExactlyInAnyOrder(String.valueOf(store1), String.valueOf(store2));

        // 到店1 → 觸發提醒(證明綁定是活的)
        String eventBody = mockMvc.perform(post("/api/location-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType": "ENTER", "latitude": %f, "longitude": 120.10}
                                """.formatted(lat + 0.0005)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(eventBody).get("triggeredReminderIds")).hasSize(1);
    }

    /** 標題沒提到任何已知品項 → 不綁任何地點。 */
    @Test
    void taskWithoutKnownItemGetsNoRules() throws Exception {
        long taskId = createTask("完全無關的待辦事項").get("id").asLong();

        assertThat(rulesOf(taskId)).isEmpty();
    }

    /**
     * 兩個品項共用同一地點 → 只綁一條規則(去重)。
     * 注意:品項名用獨特字串,因為比對是「包含」——名稱若是其他測試品項的超集
     * (如「測試蛤蜊」包含 ItemApiTest 的「蛤蜊」)會跨測試誤綁。
     */
    @Test
    void overlappingItemsBindPlaceOnlyOnce() throws Exception {
        long store = createPlace("去重測試店", 20.80);
        createItem("去重甲品", store);
        createItem("去重乙品", store);

        long taskId = createTask("買去重甲品和去重乙品").get("id").asLong();

        assertThat(rulesOf(taskId)).hasSize(1);
    }
}
