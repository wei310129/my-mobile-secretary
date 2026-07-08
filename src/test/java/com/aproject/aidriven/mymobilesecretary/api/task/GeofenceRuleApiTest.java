package com.aproject.aidriven.mymobilesecretary.api.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * geofence 規則 API 整合測試:任務綁地點。
 */
class GeofenceRuleApiTest extends IntegrationTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    private long createTask() throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "買 Tabasco"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createPlace() throws Exception {
        String body = mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "全聯", "latitude": 25.03, "longitude": 121.56, "type": "超市"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    @Test
    void createRuleReturns201AndEnabledByDefault() throws Exception {
        long taskId = createTask();
        long placeId = createPlace();

        mockMvc.perform(post("/api/tasks/{taskId}/geofence-rules", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "radiusMeters": 200, "triggerType": "ENTER"}
                                """.formatted(placeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.placeId").value(placeId))
                .andExpect(jsonPath("$.radiusMeters").value(200))
                .andExpect(jsonPath("$.triggerType").value("ENTER"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    /** 任務不存在 → 404,且不得建立規則。 */
    @Test
    void createRuleForUnknownTaskReturns404() throws Exception {
        long placeId = createPlace();

        mockMvc.perform(post("/api/tasks/{taskId}/geofence-rules", 999_999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "radiusMeters": 200, "triggerType": "ENTER"}
                                """.formatted(placeId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 地點不存在 → 404。 */
    @Test
    void createRuleForUnknownPlaceReturns404() throws Exception {
        long taskId = createTask();

        mockMvc.perform(post("/api/tasks/{taskId}/geofence-rules", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": 999999, "radiusMeters": 200, "triggerType": "ENTER"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 半徑低於下限 → 400(Bean Validation 擋在 API 層)。 */
    @Test
    void createRuleWithTooSmallRadiusReturns400() throws Exception {
        long taskId = createTask();
        long placeId = createPlace();

        mockMvc.perform(post("/api/tasks/{taskId}/geofence-rules", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "radiusMeters": 30, "triggerType": "ENTER"}
                                """.formatted(placeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("radiusMeters"));
    }
}
