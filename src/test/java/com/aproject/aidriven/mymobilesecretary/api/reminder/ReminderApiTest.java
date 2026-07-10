package com.aproject.aidriven.mymobilesecretary.api.reminder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderDelivery;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderDeliveryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 提醒 API 整合測試:從 geofence 命中產生提醒,到查詢、確認、送出紀錄的完整驗證。
 *
 * 座標策略同 LocationEventApiTest:每個測試用獨立座標區,避免跨測試干擾。
 */
class ReminderApiTest extends IntegrationTestBase {

    private static final double NEAR_OFFSET = 0.0005;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReminderDeliveryRepository deliveryRepository;

    /** 建 task + place + ENTER 規則,回報進入事件,回傳觸發的 reminder id。 */
    private long triggerReminder(String title, double baseLat) throws Exception {
        String taskBody = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s"}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long taskId = objectMapper.readTree(taskBody).get("id").asLong();

        String placeBody = mockMvc.perform(post("/api/places")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "測試點", "latitude": %f, "longitude": 120.20}
                                """.formatted(baseLat)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long placeId = objectMapper.readTree(placeBody).get("id").asLong();

        mockMvc.perform(post("/api/tasks/{taskId}/geofence-rules", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "radiusMeters": 200, "triggerType": "ENTER"}
                                """.formatted(placeId)))
                .andExpect(status().isCreated());

        String eventBody = mockMvc.perform(post("/api/location-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventType": "ENTER", "latitude": %f, "longitude": 120.20}
                                """.formatted(baseLat + NEAR_OFFSET)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(eventBody).get("triggeredReminderIds").get(0).asLong();
    }

    /** 驗收核心:geofence 命中後,送出紀錄有 LOG 通道的成功紀錄。 */
    @Test
    void triggeredReminderHasSuccessfulLogDelivery() throws Exception {
        long reminderId = triggerReminder("買排骨", 22.60);

        List<ReminderDelivery> deliveries = deliveryRepository.findByReminderId(reminderId);
        assertThat(deliveries)
                .extracting(ReminderDelivery::getChannel, ReminderDelivery::isSuccess)
                .contains(org.assertj.core.groups.Tuple.tuple("LOG", true));
    }

    @Test
    void listRemindersContainsTriggeredReminder() throws Exception {
        triggerReminder("買白蘿蔔", 22.40);

        mockMvc.perform(get("/api/reminders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].status").exists())
                .andExpect(jsonPath("$[0].triggerReason").exists());
    }

    @Test
    void getReminderReturnsDetails() throws Exception {
        long reminderId = triggerReminder("買 Tabasco", 22.20);

        mockMvc.perform(get("/api/reminders/{id}", reminderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reminderId))
                .andExpect(jsonPath("$.status").value("TRIGGERED"))
                .andExpect(jsonPath("$.triggerReason").value("ENTER geofence: 測試點"));
    }

    @Test
    void getUnknownReminderReturns404() throws Exception {
        mockMvc.perform(get("/api/reminders/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void confirmReminderSetsStatusAndTime() throws Exception {
        long reminderId = triggerReminder("買蛤蜊", 22.00);

        mockMvc.perform(patch("/api/reminders/{id}/confirm", reminderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").exists());
    }

    /** 重複確認 → 422。 */
    @Test
    void confirmTwiceReturns422() throws Exception {
        long reminderId = triggerReminder("已確認提醒", 21.80);
        mockMvc.perform(patch("/api/reminders/{id}/confirm", reminderId)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/reminders/{id}/confirm", reminderId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void confirmUnknownReminderReturns404() throws Exception {
        mockMvc.perform(patch("/api/reminders/{id}/confirm", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
